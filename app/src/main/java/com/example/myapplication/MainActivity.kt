package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ==========================================
// CUSTOM THEME DEFINITIONS
// ==========================================

private val CraftingPrimary = ComposeColor(0xFFD84B6B)
private val CraftingOnPrimary = ComposeColor(0xFFFFFFFF)
private val CraftingPrimaryContainer = ComposeColor(0xFFFFD9E2)
private val CraftingOnPrimaryContainer = ComposeColor(0xFF3B071B)
private val CraftingSecondary = ComposeColor(0xFF006B5F)
private val CraftingOnSecondary = ComposeColor(0xFFFFFFFF)
private val CraftingSecondaryContainer = ComposeColor(0xFFB2EBF2)
private val CraftingOnSecondaryContainer = ComposeColor(0xFF00201C)
private val CraftingBackground = ComposeColor(0xFFFFF8F5)
private val CraftingSurface = ComposeColor(0xFFFFFFFF)
private val CraftingSurfaceVariant = ComposeColor(0xFFF4EBE8)
private val CraftingOnSurfaceVariant = ComposeColor(0xFF524441)

private val CraftingColorScheme = lightColorScheme(
    primary = CraftingPrimary, onPrimary = CraftingOnPrimary,
    primaryContainer = CraftingPrimaryContainer, onPrimaryContainer = CraftingOnPrimaryContainer,
    secondary = CraftingSecondary, onSecondary = CraftingOnSecondary,
    secondaryContainer = CraftingSecondaryContainer, onSecondaryContainer = CraftingOnSecondaryContainer,
    background = CraftingBackground, surface = CraftingSurface,
    surfaceVariant = CraftingSurfaceVariant, onSurfaceVariant = CraftingOnSurfaceVariant
)

private val CraftingTypography = Typography(
    titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = 0.5.sp),
    headlineMedium = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp)
)

private val CraftingShapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))

@Composable
fun CrochetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CraftingColorScheme, typography = CraftingTypography, shapes = CraftingShapes, content = content)
}

// ==========================================
// MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrochetTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrochetPatternGeneratorScreen()
                }
            }
        }
    }
}

@Composable
fun CrochetPatternGeneratorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- EASTER EGG STATE ---
    var secretTapCount by remember { mutableStateOf(0) }

    // --- PERSISTENT STATE VARIABLES ("Save My Spot" Feature) ---
    var selectedImageUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedImageUri = remember(selectedImageUriString) { selectedImageUriString?.let { Uri.parse(it) } }

    var currentRow by rememberSaveable { mutableStateOf(1) }
    var stitchWidth by rememberSaveable { mutableStateOf(40f) }
    var maxColors by rememberSaveable { mutableStateOf(6f) }
    var gaugeStitches by rememberSaveable { mutableStateOf(15f) }
    var gaugeRows by rememberSaveable { mutableStateOf(18f) }
    var removeBackground by rememberSaveable { mutableStateOf(false) }

    val stitchOptions = listOf("Single Crochet (SC)", "C2C (Diagonal)", "Double Crochet (DC Pairs)", "2x2 SC Grid", "Bobble Stitch")
    var selectedStitch by rememberSaveable { mutableStateOf(stitchOptions[0]) }

    // Standard memory states
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pixelatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var optimalRows by remember { mutableStateOf(0) }
    var currentGaugeRatio by remember { mutableStateOf(1.0f) }
    var activeColorMap by remember { mutableStateOf<Map<String, IntArray>>(emptyMap()) }
    var writtenInstruction by remember { mutableStateOf("") }
    var yarnStats by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var expandedStitchMenu by remember { mutableStateOf(false) }
    var myStash by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showStashDialog by remember { mutableStateOf(false) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var showColorsDialog by remember { mutableStateOf(false) }

    val totalRows = remember(pixelatedBitmap, stitchWidth, selectedStitch) {
        pixelatedBitmap?.let { bmp ->
            if (selectedStitch == "C2C (Diagonal)") bmp.width + bmp.height - 1 else bmp.height
        } ?: 1
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUriString = uri?.toString()
        showSettingsPage = true
    }

    LaunchedEffect(selectedImageUri) {
        selectedImageUri?.let { uri ->
            try {
                val decodedBitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            decoder.isMutableRequired = true
                            val maxSize = 1200
                            val scaleDown = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
                            if (scaleDown < 1f) decoder.setTargetSize((info.size.width * scaleDown).toInt(), (info.size.height * scaleDown).toInt())
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }
                }
                originalBitmap = decodedBitmap
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(originalBitmap, stitchWidth, maxColors, removeBackground, myStash, gaugeStitches, gaugeRows) {
        kotlinx.coroutines.delay(300)
        originalBitmap?.let { src ->
            val dimensions = ImageProcessor.getOptimalDimensions(src, stitchWidth.roundToInt(), gaugeStitches, gaugeRows)
            optimalRows = dimensions.second
            currentGaugeRatio = gaugeRows / gaugeStitches

            val processed = withContext(Dispatchers.IO) {
                val workingBitmap = if (removeBackground) ImageProcessor.isolateSubject(src) else src
                val gridBmp = ImageProcessor.pixelate(workingBitmap, dimensions.first, dimensions.second)
                val newColorMap = if (myStash.isNotEmpty()) {
                    ImageProcessor.loadColorMap(context).filterKeys { it in myStash }
                } else {
                    val dominant = ImageProcessor.extractDominantPalette(gridBmp, maxColors.roundToInt())
                    ImageProcessor.fetchDynamicColorMap(dominant)
                }
                activeColorMap = newColorMap
                val paletteColors = newColorMap.values.map { android.graphics.Color.rgb(it[0], it[1], it[2]) }
                ImageProcessor.quantizeColors(gridBmp, paletteColors)
            }
            pixelatedBitmap = processed
            currentRow = 1
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(pixelatedBitmap, activeColorMap) {
        pixelatedBitmap?.let { bmp ->
            withContext(Dispatchers.IO) { yarnStats = ImageProcessor.calculateYarnRequirements(bmp, activeColorMap) }
        }
    }

    LaunchedEffect(pixelatedBitmap, currentRow, selectedStitch, activeColorMap) {
        pixelatedBitmap?.let { bmp ->
            if (selectedStitch == "C2C (Diagonal)") {
                val diagonalIndex = currentRow - 1
                writtenInstruction = ImageProcessor.getC2CRowInstructions(bmp, diagonalIndex, activeColorMap)
            } else {
                val bitmapRowIndex = bmp.height - currentRow
                val isOddRow = currentRow % 2 != 0
                val stitchAbbr = when (selectedStitch) {
                    "Double Crochet (DC Pairs)" -> "DC-Pair"
                    "2x2 SC Grid" -> "SC"
                    "Bobble Stitch" -> "Bobble"
                    else -> "SC"
                }
                writtenInstruction = ImageProcessor.getWrittenRowInstructions(bmp, bitmapRowIndex, isOddRow, stitchAbbr, activeColorMap)
            }
        }
    }

    if (showStashDialog) {
        val allColors = remember { ImageProcessor.loadColorMap(context).keys.toList().sorted() }
        AlertDialog(
            onDismissRequest = { showStashDialog = false },
            title = { Text("My Yarn Stash") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(allColors) { colorName ->
                        val isChecked = myStash.contains(colorName)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isChecked, onCheckedChange = { checked ->
                                val newStash = myStash.toMutableSet()
                                if (checked) newStash.add(colorName) else newStash.remove(colorName)
                                myStash = newStash
                            })
                            Text(text = colorName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStashDialog = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { myStash = emptySet() }) { Text("Clear All") } }
        )
    }

    if (showColorsDialog) {
        AlertDialog(
            onDismissRequest = { showColorsDialog = false },
            title = { Text("Required Yarn Colors") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(yarnStats.keys.toList()) { colorName ->
                        Text(text = "• $colorName", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColorsDialog = false }) { Text("Close") } }
        )
    }

    if (showSettingsPage) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSettingsPage = false }) { Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to Canvas") }
                Text(text = "Project Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp))
            }

            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp).animateContentSize(animationSpec = tween(400)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Image Tuning", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Isolate Subject (AI)", style = MaterialTheme.typography.bodyLarge)
                            Text(text = "Erase background", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = removeBackground, onCheckedChange = { removeBackground = it })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Stitch Width: ${stitchWidth.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = stitchWidth, onValueChange = { stitchWidth = it }, valueRange = 10f..200f, steps = 189)
                }
            }

            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp).animateContentSize(animationSpec = tween(400)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Colors & Yarn", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = if (myStash.isEmpty()) "Using Web Colors" else "Using Stash (${myStash.size})", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { showStashDialog = true }) { Text("My Stash") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (myStash.isEmpty()) {
                        Text(text = "Max Yarn Colors: ${maxColors.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
                        Slider(value = maxColors, onValueChange = { maxColors = it }, valueRange = 2f..50f, steps = 47)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp).animateContentSize(animationSpec = tween(400)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Gauge & Tension Calibration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Pattern Stitch Type:", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = { expandedStitchMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedStitch) }
                    DropdownMenu(expanded = expandedStitchMenu, onDismissRequest = { expandedStitchMenu = false }) {
                        stitchOptions.forEach { selectionOption ->
                            DropdownMenuItem(text = { Text(selectionOption) }, onClick = {
                                selectedStitch = selectionOption
                                expandedStitchMenu = false
                                if (selectionOption == "2x2 SC Grid") { gaugeStitches = 15f; gaugeRows = 15f }
                                currentRow = 1
                            })
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Per 4-inch square:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "Stitches Wide: ${gaugeStitches.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = gaugeStitches, onValueChange = { gaugeStitches = it }, valueRange = 1f..30f, steps = 28)
                    Text(text = "Rows Tall: ${gaugeRows.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = gaugeRows, onValueChange = { gaugeRows = it }, valueRange = 1f..30f, steps = 28)

                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Software-Calculated Pattern:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tension Ratio: ${"%.2f".format(currentGaugeRatio)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Optimal Grid: ${stitchWidth.roundToInt()} sts x $optimalRows rows", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Button(onClick = { showSettingsPage = false }, modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp).height(56.dp), shape = MaterialTheme.shapes.large) {
                Text("Apply Settings & View Canvas", style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {

                // EASTER EGG IMPLEMENTATION
                Text(
                    text = "Pixelator",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                secretTapCount++
                                if (secretTapCount == 5) {
                                    Toast.makeText(context, "✨ Built with love by Habil ✨", Toast.LENGTH_LONG).show()
                                    secretTapCount = 0
                                }
                            }
                        )
                    }
                )

                if (yarnStats.isNotEmpty()) {
                    IconButton(onClick = { showColorsDialog = true }) { Icon(imageVector = Icons.Default.Info, contentDescription = "View Colors", tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { pickMediaLauncher.launch("image/*") }) { Text("Photo") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { showSettingsPage = true }) { Text("⚙️ Settings") }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    pixelatedBitmap?.let { bmp ->
                        coroutineScope.launch {
                            Toast.makeText(context, "Generating high-res pattern...", Toast.LENGTH_SHORT).show()
                            val success = withContext(Dispatchers.IO) {
                                val printable = ImageProcessor.generatePrintablePattern(bmp)
                                ImageProcessor.saveBitmapToGallery(context, printable)
                            }
                            if (success) Toast.makeText(context, "Saved to Pictures!", Toast.LENGTH_LONG).show()
                            else Toast.makeText(context, "Failed to save.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, enabled = pixelatedBitmap != null) { Icon(imageVector = Icons.Default.Download, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary) }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 10f)
                            val maxPan = 1500f * scale
                            val newOffsetX = offset.x + pan.x * scale
                            val newOffsetY = offset.y + pan.y * scale
                            if (scale > 1f) offset = Offset(newOffsetX.coerceIn(-maxPan, maxPan), newOffsetY.coerceIn(-maxPan, maxPan))
                            else offset = Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = pixelatedBitmap, animationSpec = tween(800), label = "image_fade") { currentBmp ->
                    currentBmp?.let { bmp ->
                        val aspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                        Box(modifier = Modifier.aspectRatio(aspectRatio).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val rowHeight = size.height / bmp.height
                                val colWidth = size.width / bmp.width
                                val lightColor = ComposeColor.Black.copy(alpha = 0.2f)
                                val heavyColor = ComposeColor.Black.copy(alpha = 0.7f)

                                for (i in 0..bmp.width) {
                                    val xPos = i * colWidth
                                    drawLine(color = if (i % 10 == 0) heavyColor else lightColor, start = Offset(xPos, 0f), end = Offset(xPos, size.height), strokeWidth = if (i % 10 == 0) 3f else 1f)
                                }
                                for (i in 0..bmp.height) {
                                    val yPos = i * rowHeight
                                    drawLine(color = if (i % 10 == 0) heavyColor else lightColor, start = Offset(0f, yPos), end = Offset(size.width, yPos), strokeWidth = if (i % 10 == 0) 3f else 1f)
                                }

                                if (selectedStitch == "C2C (Diagonal)") {
                                    val k = currentRow - 1
                                    val minU = maxOf(0, k - (bmp.height - 1))
                                    val maxU = minOf(k, bmp.width - 1)
                                    for (u in minU..maxU) {
                                        val v = k - u
                                        val x = bmp.width - 1 - u
                                        val y = bmp.height - 1 - v
                                        drawRect(
                                            color = ComposeColor.Yellow.copy(alpha = 0.5f),
                                            topLeft = Offset(x * colWidth, y * rowHeight),
                                            size = androidx.compose.ui.geometry.Size(colWidth, rowHeight)
                                        )
                                    }
                                } else {
                                    val highlightY = size.height - (currentRow * rowHeight)
                                    drawRect(color = ComposeColor.Yellow.copy(alpha = 0.4f), topLeft = Offset(0f, highlightY), size = androidx.compose.ui.geometry.Size(size.width, rowHeight))
                                }
                            }
                        }
                    } ?: run {
                        if (selectedImageUri != null) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        else Text(text = "Upload a photo to begin.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (pixelatedBitmap != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Card(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).padding(bottom = 12.dp).animateContentSize(animationSpec = tween(300)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        val repeatText = if (selectedStitch == "2x2 SC Grid") " (Repeat row twice!)" else ""
                        val directionText = if (selectedStitch == "C2C (Diagonal)") {
                            if (currentRow % 2 != 0) "Bottom-Up" else "Top-Down"
                        } else {
                            if (currentRow % 2 != 0) "Right to Left" else "Left to Right"
                        }
                        Text(text = "Row $currentRow (Read $directionText)$repeatText:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = writtenInstruction, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, lineHeight = 26.sp)
                    }
                }

                AnimatedVisibility(visible = yarnStats.isNotEmpty()) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(yarnStats.toList()) { (colorName, count) ->
                            val multiplier = when(selectedStitch) {
                                "C2C (Diagonal)" -> 18.0
                                "Double Crochet (DC Pairs)", "2x2 SC Grid" -> 9.6
                                "Bobble Stitch" -> 15.0
                                else -> 2.4
                            }
                            val inches = (count * multiplier).roundToInt().coerceAtLeast(1)
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(text = "$colorName: $count sts (~$inches in)", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (currentRow > 1) currentRow-- }, enabled = currentRow > 1) { Text("Prev") }
                    Text(text = "Row $currentRow of $totalRows", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { if (currentRow < totalRows) currentRow++ }, enabled = currentRow < totalRows) { Text("Next") }
                }
            }
        }
    }
}