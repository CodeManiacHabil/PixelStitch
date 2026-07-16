package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import kotlin.math.roundToInt
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

object ImageProcessor {

    private var cachedColorMap: Map<String, IntArray>? = null

    /**
     * Precisely scales the image to force a 1:1 physical aspect ratio.
     * Returns the required pixel-grid dimensions to the UI so the user knows
     * exactly what the software has determined is "optimal".
     */
    fun getOptimalDimensions(bitmap: Bitmap, targetWidth: Int, gaugeStitches: Float, gaugeRows: Float): Pair<Int, Int> {
        val originalAspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()

        // This is the "Golden Ratio" of the user's tension
        val stitchToRowRatio = gaugeRows / gaugeStitches

        // We calculate height based on the ratio to force a square
        val targetHeight = (targetWidth * originalAspectRatio * stitchToRowRatio).roundToInt().coerceAtLeast(1)

        return Pair(targetWidth, targetHeight)
    }

    fun pixelate(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Optimized using bulk array manipulation and squared distances.
     */
    fun quantizeColors(src: Bitmap, palette: List<Int>): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            pixels[i] = findNearestPaletteColor(pixels[i], palette)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Optimized with downsampling and squared distance logic to isolate unique colors quickly.
     */
    fun extractDominantPalette(src: Bitmap, maxColors: Int): List<Int> {
        val colorCounts = mutableMapOf<Int, Int>()
        val width = src.width
        val height = src.height

        // Downsample via stepped retrieval
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val color = src.getPixel(x, y)
                val roundedColor = Color.rgb(
                    (Color.red(color) / 8) * 8,
                    (Color.green(color) / 8) * 8,
                    (Color.blue(color) / 8) * 8
                )
                colorCounts[roundedColor] = (colorCounts[roundedColor] ?: 0) + 1
            }
        }

        val sortedColors = colorCounts.entries.sortedByDescending { it.value }.map { it.key }
        val diversePalette = mutableListOf<Int>()
        val thresholdSq = 20.0 * 20.0 // 400.0 instead of sqrt checks

        for (color in sortedColors) {
            if (diversePalette.size >= maxColors) break
            var isTooSimilar = false
            val r1 = Color.red(color)
            val g1 = Color.green(color)
            val b1 = Color.blue(color)

            for (pickedColor in diversePalette) {
                val rDiff = r1 - Color.red(pickedColor)
                val gDiff = g1 - Color.green(pickedColor)
                val bDiff = b1 - Color.blue(pickedColor)
                val distanceSq = (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff).toDouble()

                if (distanceSq < thresholdSq) {
                    isTooSimilar = true
                    break
                }
            }
            if (!isTooSimilar) diversePalette.add(color)
        }

        if (diversePalette.size < maxColors) {
            for (color in sortedColors) {
                if (diversePalette.size >= maxColors) break
                if (!diversePalette.contains(color)) diversePalette.add(color)
            }
        }
        return diversePalette
    }

    /**
     * THE ONLINE API BRIDGE: Takes raw colors, asks the internet for names, and builds a custom dictionary.
     */
    suspend fun fetchDynamicColorMap(colors: List<Int>): Map<String, IntArray> {
        return withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, IntArray>()
            try {
                val hexes = colors.map { String.format("%06X", 0xFFFFFF and it) }
                val urlString = "https://api.color.pizza/v1/?values=${hexes.joinToString(",")}"

                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(response)
                    val colorsArray = jsonObject.getJSONArray("colors")

                    for (i in 0 until colorsArray.length()) {
                        val item = colorsArray.getJSONObject(i)
                        val name = item.getString("name")
                        val hex = item.getString("hex")
                        val colorInt = Color.parseColor(hex)

                        var finalName = name
                        var counter = 2
                        while (map.containsKey(finalName)) {
                            finalName = "$name $counter"
                            counter++
                        }

                        map[finalName] = intArrayOf(Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                    }
                    return@withContext map
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Safety Fallback: Avoid app crashes if network fails
            colors.forEachIndexed { index, color ->
                map["Color ${index + 1}"] = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
            }
            map
        }
    }

    /**
     * Speed-optimized palette matching using squared distance logic.
     */
    private fun findNearestPaletteColor(color: Int, palette: List<Int>): Int {
        if (palette.isEmpty()) return color
        var nearestColor = palette[0]
        var minDistanceSq = Int.MAX_VALUE
        val r1 = Color.red(color)
        val g1 = Color.green(color)
        val b1 = Color.blue(color)

        for (paletteColor in palette) {
            val rDiff = Color.red(paletteColor) - r1
            val gDiff = Color.green(paletteColor) - g1
            val bDiff = Color.blue(paletteColor) - b1
            val distanceSq = rDiff * rDiff + gDiff * gDiff + bDiff * bDiff

            if (distanceSq < minDistanceSq) {
                minDistanceSq = distanceSq
                nearestColor = paletteColor
            }
        }
        return nearestColor
    }

    fun loadColorMap(context: Context): Map<String, IntArray> {
        if (cachedColorMap != null) return cachedColorMap!!
        val map = mutableMapOf<String, IntArray>()
        try {
            val inputStream = context.assets.open("yarn_colors.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val name = item.getString("name")
                val rgbArray = item.getJSONArray("rgb")
                map[name] = intArrayOf(rgbArray.getInt(0), rgbArray.getInt(1), rgbArray.getInt(2))
            }
            cachedColorMap = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun getWrittenRowInstructions(bitmap: Bitmap, rowIndex: Int, isOddRow: Boolean, stitchAbbr: String = "SC", activeColorMap: Map<String, IntArray>): String {
        val instructions = mutableListOf<String>()
        var currentColor = ""
        var count = 0
        val width = bitmap.width

        // Optimize row retrieval using a partial bulk array buffer row
        val rowPixels = IntArray(width)
        bitmap.getPixels(rowPixels, 0, width, 0, rowIndex, width, 1)

        val indexIterable = if (isOddRow) (width - 1 downTo 0) else (0 until width)

        for (x in indexIterable) {
            val colorName = getColorName(rowPixels[x], activeColorMap)

            if (colorName == currentColor) {
                count++
            } else {
                if (count > 0) instructions.add("$count $stitchAbbr $currentColor")
                currentColor = colorName
                count = 1
            }
        }
        if (count > 0) instructions.add("$count $stitchAbbr $currentColor")
        return instructions.joinToString(", ")
    }

    /**
     * Optimized using squared Euclidean distance calculations.
     */
    fun getColorName(colorInt: Int, colorMap: Map<String, IntArray>): String {
        val r1 = Color.red(colorInt)
        val g1 = Color.green(colorInt)
        val b1 = Color.blue(colorInt)
        var closestName = "Unknown"
        var minDistanceSq = Int.MAX_VALUE

        for ((name, rgb) in colorMap) {
            val rDiff = rgb[0] - r1
            val gDiff = rgb[1] - g1
            val bDiff = rgb[2] - b1
            val distanceSq = rDiff * rDiff + gDiff * gDiff + bDiff * bDiff

            if (distanceSq < minDistanceSq) {
                minDistanceSq = distanceSq
                closestName = name
            }
        }
        return closestName
    }

    suspend fun isolateSubject(source: Bitmap): Bitmap = suspendCancellableCoroutine { continuation ->
        val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        val segmenter = SubjectSegmentation.getClient(options)
        val image = InputImage.fromBitmap(source, 0)

        segmenter.process(image)
            .addOnSuccessListener { result ->
                val fgBitmap = result.foregroundBitmap
                if (fgBitmap != null) {
                    val finalBitmap = Bitmap.createBitmap(fgBitmap.width, fgBitmap.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(finalBitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.drawBitmap(fgBitmap, 0f, 0f, null)
                    continuation.resume(finalBitmap)
                } else {
                    continuation.resume(source)
                }
            }
            .addOnFailureListener {
                continuation.resume(source)
            }
    }

    /**
     * Uses bulk parsing layout to count colors rapidly.
     */
    fun calculateYarnRequirements(bitmap: Bitmap, activeColorMap: Map<String, IntArray>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (pixel in pixels) {
            val name = getColorName(pixel, activeColorMap)
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    fun generatePrintablePattern(pixelated: Bitmap): Bitmap {
        val maxGridDimension = maxOf(pixelated.width, pixelated.height)
        var cellSize = 40
        if (maxGridDimension * cellSize > 4000) cellSize = maxOf(5, 4000 / maxGridDimension)
        val axisMargin = cellSize * 2

        val width = pixelated.width * cellSize + (axisMargin * 2)
        val height = pixelated.height * cellSize + (axisMargin * 2)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.WHITE)

        val cellPaint = android.graphics.Paint()
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = cellSize * 0.7f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val gridPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.DKGRAY
            strokeWidth = maxOf(1f, cellSize / 20f)
        }
        val heavyGridPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = maxOf(2f, cellSize / 6f)
        }

        // We use getPixel here safely since it is an export step executed once outside standard loops
        for (y in 0 until pixelated.height) {
            for (x in 0 until pixelated.width) {
                cellPaint.color = pixelated.getPixel(x, y)
                val left = axisMargin + (x * cellSize).toFloat()
                val top = axisMargin + (y * cellSize).toFloat()
                canvas.drawRect(left, top, left + cellSize, top + cellSize, cellPaint)
            }
        }

        for (x in 0..pixelated.width) {
            val lineX = axisMargin + (x * cellSize).toFloat()
            val is10th = x % 10 == 0
            canvas.drawLine(lineX, axisMargin.toFloat(), lineX, height - axisMargin.toFloat(), if (is10th) heavyGridPaint else gridPaint)
        }
        for (x in 0 until pixelated.width) {
            if ((x + 1) % 10 == 0 || x == 0) {
                val textX = axisMargin + (x * cellSize) + (cellSize / 2f)
                canvas.drawText((x + 1).toString(), textX, (axisMargin / 2f) + (cellSize * 0.25f), textPaint)
                canvas.drawText((x + 1).toString(), textX, height - (axisMargin / 2f) + (cellSize * 0.25f), textPaint)
            }
        }

        for (y in 0..pixelated.height) {
            val lineY = axisMargin + (y * cellSize).toFloat()
            val is10th = y % 10 == 0
            canvas.drawLine(axisMargin.toFloat(), lineY, width - axisMargin.toFloat(), lineY, if (is10th) heavyGridPaint else gridPaint)
        }
        for (y in 0 until pixelated.height) {
            val rowNum = pixelated.height - y
            if (rowNum % 10 == 0 || rowNum == 1) {
                val textY = axisMargin + (y * cellSize) + (cellSize / 2f) + (cellSize * 0.25f)
                canvas.drawText(rowNum.toString(), (axisMargin / 2f), textY, textPaint)
                canvas.drawText(rowNum.toString(), width - (axisMargin / 2f), textY, textPaint)
            }
        }
        return output
    }

    /**
     * THE C2C ENGINE: Reads the pixel grid diagonally from the bottom-right corner to the top-left.
     */
    fun getC2CRowInstructions(bitmap: Bitmap, diagonalIndex: Int, activeColorMap: Map<String, IntArray>): String {
        val w = bitmap.width
        val h = bitmap.height
        val k = diagonalIndex // 0 to w+h-2

        // Determine the boundaries for the diagonal math
        val minU = maxOf(0, k - (h - 1))
        val maxU = minOf(k, w - 1)

        val coords = mutableListOf<Pair<Int, Int>>()
        for (u in minU..maxU) {
            val v = k - u
            val x = w - 1 - u  // Starts at bottom right
            val y = h - 1 - v
            coords.add(Pair(x, y))
        }

        // C2C alternates direction every single row (Up-Right vs Down-Left)
        if (k % 2 != 0) {
            coords.reverse()
        }

        val instructions = mutableListOf<String>()
        var currentColor = ""
        var count = 0

        for ((x, y) in coords) {
            val colorInt = bitmap.getPixel(x, y)
            val colorName = getColorName(colorInt, activeColorMap)

            if (colorName == currentColor) {
                count++
            } else {
                if (count > 0) instructions.add("$count Block $currentColor")
                currentColor = colorName
                count = 1
            }
        }
        if (count > 0) instructions.add("$count Block $currentColor")
        return instructions.joinToString(", ")
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val filename = "CrochetPattern_${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CrochetPixelator")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false

        }
    }
}
