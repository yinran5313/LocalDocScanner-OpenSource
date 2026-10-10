package com.localdoc.scanner.edit

import android.graphics.Bitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.cv.Point
import com.localdoc.scanner.cv.DocumentQuality
import com.localdoc.scanner.cv.Quad
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.canny
import com.localdoc.scanner.cv.detectDocumentQuad
import com.localdoc.scanner.cv.outputSizeFor
import com.localdoc.scanner.cv.processScanBitmap
import com.localdoc.scanner.cv.scaleBitmap
import com.localdoc.scanner.cv.toGray
import com.localdoc.scanner.cv.warpPerspective
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val MAX_OUTPUT_SIDE = 3200
private const val PREVIEW_SIDE = 1400

private enum class EditStep { CROP, ENHANCE }

private data class StableState(
    val quarterTurns: Int,
    val corners: List<NormalizedPoint>,
    val filter: ScanFilter,
    val brightness: Float,
    val contrast: Float,
    val fineRotation: Float, val cropRatio: Float
)

@Composable
fun EditScreen(
    sourcePath: String,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    initialRecipe: EditRecipe? = null,
    onConfirm: suspend (EditResult) -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit
) {
    val ownership = remember { com.localdoc.scanner.util.BitmapOwnership() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var step by remember { mutableStateOf(EditStep.CROP) }
    var quarterTurns by remember { mutableIntStateOf(initialRecipe?.quarterTurns ?: 0) }
    var corners by remember { mutableStateOf(initialRecipe?.corners ?: defaultCropCorners()) }
    var filter by remember { mutableStateOf(initialRecipe?.filter ?: ScanFilter.AUTO) }
    var brightness by remember { mutableFloatStateOf(initialRecipe?.brightness ?: 0f) }
    var contrast by remember { mutableFloatStateOf(initialRecipe?.contrast ?: 1f) }
    var fineRotation by remember { mutableFloatStateOf(initialRecipe?.fineRotation ?: 0f) }
    var cropRatio by remember { mutableFloatStateOf(initialRecipe?.cropRatio ?: 0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showOriginal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = busy) {}
    var qualityWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf<List<StableState>>(emptyList()) }
    var future by remember { mutableStateOf<List<StableState>>(emptyList()) }

    fun snapshot() = StableState(quarterTurns, corners, filter, brightness, contrast, fineRotation, cropRatio)
    fun pushUndo() {
        val now = snapshot()
        if (history.lastOrNull() != now) history = (history + now).takeLast(30)
        future = emptyList()
    }
    fun undo() {
        val last = history.lastOrNull() ?: return
        future = (future + snapshot()).takeLast(30)
        history = history.dropLast(1)
        quarterTurns = last.quarterTurns
        corners = last.corners
        filter = last.filter
        brightness = last.brightness
        contrast = last.contrast
        fineRotation = last.fineRotation; cropRatio = last.cropRatio
    }
    fun redo() {
        val next = future.lastOrNull() ?: return
        history = (history + snapshot()).takeLast(30)
        future = future.dropLast(1)
        quarterTurns = next.quarterTurns
        corners = next.corners
        filter = next.filter
        brightness = next.brightness
        contrast = next.contrast
        fineRotation = next.fineRotation; cropRatio = next.cropRatio
    }

    LaunchedEffect(sourcePath) {
        var pending: Bitmap? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                ImageIo.loadFromFile(File(sourcePath), MAX_OUTPUT_SIDE)?.also { pending = ownership.adopt(it) }
            }
            source = loaded; pending = null
            loaded?.let { bitmap ->
                if (ownership.acquire(bitmap)) try {
                    val warnings = withContext(Dispatchers.Default) { DocumentQuality.analyze(bitmap).warnings }
                    if (initialRecipe == null) {
                        val detected = withContext(Dispatchers.Default) { autoCornersNormalized(bitmap) }
                        corners = detected
                        qualityWarnings = if (detected == defaultCropCorners()) warnings + "没有识别到完整纸张边缘，请检查四个角" else warnings
                    } else qualityWarnings = warnings
                } finally { ownership.release(bitmap) }
            }
        } finally { ownership.retire(pending) }
    }

    var rotatedPreview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, quarterTurns) {
        val input = source ?: return@LaunchedEffect
        if (!ownership.acquire(input)) return@LaunchedEffect
        var pending: Bitmap? = null
        try {
            val next = withContext(Dispatchers.Default) { ImageIo.rotate(input, quarterTurns * 90f).also { pending = ownership.adopt(it) } }
            val old = rotatedPreview; rotatedPreview = next; pending = null
            if (old !== source && old !== next) ownership.retire(old)
        } finally { if (pending !== source) ownership.retire(pending); ownership.release(input) }
    }

    var processedPreview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(rotatedPreview, corners, filter, brightness, contrast, fineRotation, cropRatio, step) {
        if (step != EditStep.ENHANCE) return@LaunchedEffect
        delay(90)
        val input = rotatedPreview ?: return@LaunchedEffect
        if (!ownership.acquire(input)) return@LaunchedEffect
        var pending: Bitmap? = null
        try {
            val output = withContext(Dispatchers.Default) {
                renderProcessed(input, corners, filter, brightness, contrast, PREVIEW_SIDE, fineRotation, cropRatio).also { pending = ownership.adopt(it) }
            }
            val old = processedPreview; processedPreview = output; pending = null
            if (old !== rotatedPreview && old !== output) ownership.retire(old)
        } finally { if (pending !== input) ownership.retire(pending); ownership.release(input) }
    }
    DisposableEffect(Unit) { onDispose { ownership.dispose() } }

    fun rotate() {
        pushUndo()
        quarterTurns = (quarterTurns + 1) % 4
        corners = rotateCropClockwise(corners)
        if(cropRatio > 0f) cropRatio = 1f / cropRatio
        zoom = 1f
        pan = Offset.Zero
    }

    fun redetect() {
        val original = source ?: return
        pushUndo()
        scope.launch {
            val detected = withContext(Dispatchers.Default) { autoCornersNormalized(original) }
            var rotated = detected
            repeat(quarterTurns) { rotated = rotateCropClockwise(rotated) }
            corners = rotatedPreview?.let { CropAspect.fit(rotated, cropRatio, it.width.toFloat()/it.height) } ?: rotated
            zoom = 1f
            pan = Offset.Zero
        }
    }

    fun save() {
        val original = source ?: return
        if (busy || !ownership.acquire(original)) return
        val recipe = EditRecipe(quarterTurns, corners.toList(), filter, brightness, contrast, fineRotation, cropRatio)
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val rotated = ImageIo.rotate(original, recipe.quarterTurns * 90f)
                    var rendered: Bitmap? = null
                    try {
                        rendered = renderProcessed(rotated, recipe.corners, recipe.filter, recipe.brightness, recipe.contrast, MAX_OUTPUT_SIDE, recipe.fineRotation, recipe.cropRatio)
                        val out = File(FileStore.draftWorkDir(context), "render_${java.util.UUID.randomUUID()}.jpg")
                        check(ImageIo.saveJpeg(rendered, out, 94)) { "无法保存页面，请检查手机空间" }
                        EditResult(File(sourcePath), out, recipe, rendered.width, rendered.height)
                    } finally { if (rendered !== rotated) rendered?.recycle(); if (rotated !== original) rotated.recycle() }
                }
                onConfirm(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.widget.Toast.makeText(context, "保存失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } finally { ownership.release(original); busy = false }
        }
    }

    @Composable fun editorPreview(previewModifier: Modifier) {
        Box(modifier = previewModifier.background(Color.Black).testTag("scan-preview")) {
            when (step) {
                EditStep.CROP -> {
                    val preview = rotatedPreview
                    if (preview != null) {
                        CropCanvas(
                            bitmap = preview,
                            corners = corners,
                            zoom = zoom,
                            pan = pan,
                            onZoomPan = { newZoom, newPan -> zoom = newZoom; pan = newPan },
                            onGestureStart = { pushUndo() },
                            onCornersChanged = { corners = CropAspect.fit(it, cropRatio, preview.width.toFloat()/preview.height) },
                            onResetViewport = { zoom = 1f; pan = Offset.Zero },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在读取图片…", color = Color.White)
                        }
                    }
                }
                EditStep.ENHANCE -> {
                    val preview = if (showOriginal) rotatedPreview else processedPreview
                    if (preview != null) {
                        ZoomablePreview(preview, Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在生成预览…", color = Color.White)
                        }
                    }
                }
            }
            if (qualityWarnings.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Color(0xD9271D17)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    qualityWarnings.take(3).forEach { warning ->
                        Text("• $warning", color = Color(0xFFFFD7A8), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

    }
    @Composable fun editorControls() {
        if (step == EditStep.CROP) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                CropAspect.presets.forEach { (label, ratio) -> FilterChip(cropRatio == ratio, {
                    pushUndo(); cropRatio = ratio
                    rotatedPreview?.let { image -> corners = CropAspect.fit(corners, ratio, image.width.toFloat()/image.height) }
                }, label = { Text(label) }) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { rotate() }) { Text("旋转") }
                TextButton(onClick = { redetect() }) { Text("自动取边") }
                TextButton(onClick = { pushUndo(); corners = rotatedPreview?.let { CropAspect.fit(defaultCropCorners(0f), cropRatio, it.width.toFloat()/it.height) } ?: defaultCropCorners() }) { Text("整页") }
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("适合屏幕") }
                TextButton(onClick = { undo() }, enabled = history.isNotEmpty()) { Text("撤销") }
                TextButton(onClick = { redo() }, enabled = future.isNotEmpty()) { Text("重做") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScanFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { pushUndo(); filter = option },
                        label = { Text(option.label) }
                    )
                }
            }
            AdjustmentSlider("亮度", brightness, -0.45f..0.45f, onStart = { pushUndo() }) { brightness = it }
            AdjustmentSlider("对比度", contrast, 0.65f..1.55f, onStart = { pushUndo() }) { contrast = it }
            AdjustmentSlider("纠偏", fineRotation, -15f..15f, onStart = { pushUndo() }) { fineRotation = it }
            Text(
                "微调 ${"%.1f".format(fineRotation)}°",
                color = Color(0xFFB7C0CD),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 62.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = { showOriginal = !showOriginal },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        })
                    }
                ) {
                    Text(if (showOriginal) "松开看效果" else "按住看原图")
                }
                TextButton(onClick = { undo() }, enabled = history.isNotEmpty()) { Text("撤销") }
                TextButton(onClick = { redo() }, enabled = future.isNotEmpty()) { Text("重做") }
                TextButton(onClick = {
                    pushUndo(); filter = ScanFilter.AUTO; brightness = 0f; contrast = 1f; fineRotation = 0f
                }) { Text("重置增强") }
            }
        }

    }
    @Composable fun editorFooter() {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                if (step == EditStep.ENHANCE) step = EditStep.CROP else onBack()
            }) { Text(if (step == EditStep.ENHANCE) "返回裁边" else "取消", color = Color.White) }
            Button(
                onClick = {
                    if (step == EditStep.CROP) {
                        step = EditStep.ENHANCE
                        zoom = 1f
                        pan = Offset.Zero
                    } else save()
                },
                enabled = source != null && !busy
            ) { Text(if (busy) "正在保存…" else if (step == EditStep.CROP) "下一步" else "保存此页") }
        }    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        val wide = maxWidth > maxHeight
        val controlsHeight = maxHeight * 0.52f
        Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("返回", color = Color.White) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${pageIndex + 1} 页", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (step == EditStep.CROP) "调整边缘" else "预览与增强",
                    color = Color(0xFFB7C0CD),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(onClick = onRetake, enabled = !busy) { Text("重拍", color = Color.White) }
        }

            if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                editorPreview(Modifier.weight(1.2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    editorControls(); editorFooter()
                }
            } else {
                editorPreview(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) { editorControls() }
                editorFooter()
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onStart: () -> Unit,
    onChange: (Float) -> Unit
) {
    var changing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        Slider(
            value = value,
            onValueChange = {
                if (!changing) {
                    changing = true
                    onStart()
                }
                onChange(it)
            },
            onValueChangeFinished = { changing = false },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ZoomablePreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset += pan
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                }
        ) {
            val fit = min(widthPx / bitmap.width, heightPx / bitmap.height)
            val drawWidth = bitmap.width * fit * scale
            val drawHeight = bitmap.height * fit * scale
            drawImage(
                bitmap.asImageBitmap(),
                dstOffset = IntOffset(((widthPx - drawWidth) / 2f + offset.x).toInt(), ((heightPx - drawHeight) / 2f + offset.y).toInt()),
                dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt())
            )
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    corners: List<NormalizedPoint>,
    zoom: Float,
    pan: Offset,
    onZoomPan: (Float, Offset) -> Unit,
    onGestureStart: () -> Unit,
    onCornersChanged: (List<NormalizedPoint>) -> Unit,
    onResetViewport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableIntStateOf(-1) }
    var activeEdge by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val hitRadius = with(density) { 36.dp.toPx() }
    val currentCorners by rememberUpdatedState(corners)
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)

    fun geometry(): CropGeometry = CropGeometry.create(canvasSize, bitmap.width, bitmap.height, currentZoom, currentPan)

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(bitmap, canvasSize) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    if (activeCorner >= 0 || activeEdge >= 0) return@detectTransformGestures
                    val nextZoom = (currentZoom * zoomChange).coerceIn(1f, 5f)
                    val proposed = currentPan + panChange
                    val clamped = CropGeometry.create(canvasSize, bitmap.width, bitmap.height, nextZoom, proposed).clampPan(proposed)
                    onZoomPan(nextZoom, clamped)
                }
            }
            .pointerInput(bitmap, canvasSize) {
                detectTapGestures(onDoubleTap = { onResetViewport() })
            }
            .pointerInput(bitmap, canvasSize) {
                detectDragGestures(
                    onDragStart = { down ->
                        val g = geometry()
                        val screenCorners = currentCorners.map(g::toScreen)
                        activeCorner = screenCorners.indices.minByOrNull { (screenCorners[it] - down).getDistance() }
                            ?.takeIf { (screenCorners[it] - down).getDistance() <= hitRadius } ?: -1
                        if (activeCorner < 0) {
                            val mids = screenCorners.indices.map { i -> (screenCorners[i] + screenCorners[(i + 1) % 4]) / 2f }
                            activeEdge = mids.indices.minByOrNull { (mids[it] - down).getDistance() }
                                ?.takeIf { (mids[it] - down).getDistance() <= hitRadius } ?: -1
                        }
                        if (activeCorner >= 0 || activeEdge >= 0) onGestureStart()
                    },
                    onDragEnd = { activeCorner = -1; activeEdge = -1 },
                    onDragCancel = { activeCorner = -1; activeEdge = -1 },
                    onDrag = { change, dragAmount ->
                        val g = geometry()
                        if (activeCorner >= 0) {
                            onCornersChanged(moveCornerSafely(currentCorners, activeCorner, g.toNormalized(change.position)))
                            change.consume()
                        } else if (activeEdge >= 0) {
                            onCornersChanged(
                                moveEdgeSafely(
                                    currentCorners,
                                    activeEdge,
                                    dragAmount.x / g.drawWidth,
                                    dragAmount.y / g.drawHeight
                                )
                            )
                            change.consume()
                        }
                    }
                )
            }
    ) {
        val g = CropGeometry.create(canvasSize, bitmap.width, bitmap.height, zoom, pan)
        drawImage(
            bitmap.asImageBitmap(),
            dstOffset = IntOffset(g.left.toInt(), g.top.toInt()),
            dstSize = IntSize(g.drawWidth.toInt(), g.drawHeight.toInt())
        )
        val points = corners.map(g::toScreen)
        if (points.size == 4) {
            val border = Path().apply {
                moveTo(points[0].x, points[0].y)
                lineTo(points[1].x, points[1].y)
                lineTo(points[2].x, points[2].y)
                lineTo(points[3].x, points[3].y)
                close()
            }
            drawPath(border, Color(0xFF4ADE80), style = Stroke(width = 3.dp.toPx()))
            points.indices.forEach { i ->
                drawCircle(Color.White, 12.dp.toPx(), points[i])
                drawCircle(Color(0xFF22C55E), 8.dp.toPx(), points[i])
                val next = points[(i + 1) % 4]
                val mid = (points[i] + next) / 2f
                drawCircle(Color.White, 9.dp.toPx(), mid)
                drawCircle(Color(0xFF22C55E), 5.dp.toPx(), mid)
            }
            if (activeCorner >= 0) {
                val p = corners[activeCorner]
                val bubbleCenter = Offset(
                    points[activeCorner].x.coerceIn(70.dp.toPx(), size.width - 70.dp.toPx()),
                    (points[activeCorner].y - 96.dp.toPx()).coerceAtLeast(70.dp.toPx())
                )
                val bubbleRadius = 58.dp.toPx()
                val sourceSide = max(36, min(bitmap.width, bitmap.height) / 7)
                val sourceX = (p.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val sourceY = (p.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val srcX = (sourceX - sourceSide / 2).coerceIn(0, max(0, bitmap.width - sourceSide))
                val srcY = (sourceY - sourceSide / 2).coerceIn(0, max(0, bitmap.height - sourceSide))
                val clip = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(bubbleCenter, bubbleRadius))
                }
                clipPath(clip) {
                    drawImage(
                        bitmap.asImageBitmap(),
                        srcOffset = IntOffset(srcX, srcY),
                        srcSize = IntSize(min(sourceSide, bitmap.width - srcX), min(sourceSide, bitmap.height - srcY)),
                        dstOffset = IntOffset((bubbleCenter.x - bubbleRadius).toInt(), (bubbleCenter.y - bubbleRadius).toInt()),
                        dstSize = IntSize((bubbleRadius * 2).toInt(), (bubbleRadius * 2).toInt())
                    )
                }
                drawCircle(Color.White, bubbleRadius, bubbleCenter, style = Stroke(3.dp.toPx()))
                drawLine(Color(0xFF22C55E), bubbleCenter - Offset(12.dp.toPx(), 0f), bubbleCenter + Offset(12.dp.toPx(), 0f), 2.dp.toPx())
                drawLine(Color(0xFF22C55E), bubbleCenter - Offset(0f, 12.dp.toPx()), bubbleCenter + Offset(0f, 12.dp.toPx()), 2.dp.toPx())
            }
        }
    }
}

private data class CropGeometry(
    val canvas: IntSize,
    val drawWidth: Float,
    val drawHeight: Float,
    val left: Float,
    val top: Float,
    val zoom: Float
) {
    fun toScreen(point: NormalizedPoint): Offset = Offset(left + point.x * drawWidth, top + point.y * drawHeight)
    fun toNormalized(offset: Offset): NormalizedPoint = NormalizedPoint(
        (offset.x - left) / drawWidth,
        (offset.y - top) / drawHeight
    ).clamped()

    fun clampPan(proposed: Offset): Offset {
        val baseWidth = drawWidth / zoom
        val baseHeight = drawHeight / zoom
        val maxX = max(0f, (baseWidth * zoom - canvas.width) / 2f)
        val maxY = max(0f, (baseHeight * zoom - canvas.height) / 2f)
        return Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
    }

    companion object {
        fun create(canvas: IntSize, bitmapWidth: Int, bitmapHeight: Int, zoom: Float, pan: Offset): CropGeometry {
            val canvasWidth = canvas.width.coerceAtLeast(1).toFloat()
            val canvasHeight = canvas.height.coerceAtLeast(1).toFloat()
            val fit = min(canvasWidth / bitmapWidth.coerceAtLeast(1), canvasHeight / bitmapHeight.coerceAtLeast(1))
            val width = bitmapWidth * fit * zoom
            val height = bitmapHeight * fit * zoom
            return CropGeometry(
                canvas,
                width,
                height,
                (canvasWidth - width) / 2f + pan.x,
                (canvasHeight - height) / 2f + pan.y,
                zoom
            )
        }
    }
}

private fun autoCornersNormalized(bitmap: Bitmap): List<NormalizedPoint> {
    if (com.localdoc.scanner.cv.OpenCvDocument.available()) {
        return com.localdoc.scanner.cv.OpenCvDocument.detect(bitmap)?.toList()?.map {
            NormalizedPoint(it.x / bitmap.width, it.y / bitmap.height).clamped()
        }?.takeIf { isValidCrop(it) } ?: defaultCropCorners()
    }
    val small = scaleBitmap(bitmap, 480)
    val (gray, width) = toGray(small)
    val height = small.height
    val quad = runCatching { detectDocumentQuad(canny(gray, width, height), width, height) }.getOrNull()
    if (small !== bitmap) small.recycle()
    return quad?.toList()?.map {
        NormalizedPoint(it.x / width.toFloat(), it.y / height.toFloat()).clamped()
    }?.takeIf { isValidCrop(it) } ?: defaultCropCorners()
}
