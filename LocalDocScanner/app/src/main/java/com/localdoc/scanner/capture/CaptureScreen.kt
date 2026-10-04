package com.localdoc.scanner.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import com.localdoc.scanner.cv.Point
import com.localdoc.scanner.cv.Quad
import com.localdoc.scanner.cv.canny
import com.localdoc.scanner.cv.detectDocumentQuad
import com.localdoc.scanner.cv.scaleBitmap
import com.localdoc.scanner.cv.toGray
import com.localdoc.scanner.cv.OpenCvDocument
import com.localdoc.scanner.cv.DocumentQuality
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** 一帧的检测结果：四边形已归一化到 0..1，aspect = 分析帧的宽/高 */
data class Detection(val quad: Quad, val aspect: Float)
private data class AnalyzedFrame(val detection: Detection?, val warnings: List<String>, val hash: Long?, val timeMs: Long)

@Composable
fun CaptureScreen(
    pageCount: Int,
    onCaptured: (File) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    processing: Boolean = false,
    allowContinuous: Boolean = true,
    onContinuousCaptured: (File, Boolean) -> Unit = { file, _ -> onCaptured(file) },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdownNow()
            captureExecutor.shutdownNow()
        }
    }

    var detection by remember { mutableStateOf<Detection?>(null) }
    var analyzedFrame by remember { mutableStateOf<AnalyzedFrame?>(null) }
    val captureGate = remember { AutoCaptureGate() }
    var captureHint by remember { mutableStateOf(AutoCaptureGate.State.SEARCHING.hint) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var autoEdge by remember { mutableStateOf(true) }
    var autoCapture by remember { mutableStateOf(false) }
    val currentAutoEdge by rememberUpdatedState(autoEdge)
    var busy by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    fun captureNow() {
        if (busy || processing) return
        busy = true
        errorText = null
        captureGate.markCaptured(android.os.SystemClock.elapsedRealtime(), detection?.quad, analyzedFrame?.hash)
        shoot(imageCapture, captureExecutor, context) { file ->
            busy = false
            if (file != null) {
                if (allowContinuous && mode in 1..2) onContinuousCaptured(file, mode == 2) else onCaptured(file)
            } else { captureGate.reset(); errorText = "拍照失败" }
        }
    }

    LaunchedEffect(analyzedFrame, autoCapture) {
        if (!autoCapture) return@LaunchedEffect
        val frame = analyzedFrame ?: return@LaunchedEffect
        val fire = captureGate.observe(frame.detection?.quad, frame.hash, frame.timeMs, frame.warnings.isEmpty(), busy || processing)
        captureHint = if (captureGate.state == AutoCaptureGate.State.QUALITY) frame.warnings.firstOrNull() ?: captureGate.state.hint else captureGate.state.hint
        if (fire) captureNow()
    }

    DisposableEffect(lifecycleOwner, granted) {
        if (!granted) {
            onDispose { }
        } else {
            val job = scope.launch {
                try {
                    val provider = ProcessCameraProvider.getInstance(context).await()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(480, 640))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    var previousAnalysisMs = 0L
                    analysis.setAnalyzer(analyzerExecutor) { proxy ->
                        try {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - previousAnalysisMs >= 150L && currentAutoEdge) {
                                previousAnalysisMs = now
                                val result = detect(proxy, now)
                                scope.launch { analyzedFrame = result; detection = result.detection }
                            }
                        } finally { proxy.close() }
                    }
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis
                    )
                    cameraControl = camera
                    imageCapture = capture
                } catch (e: Exception) {
                    errorText = "相机启动失败：${e.message}"
                }
            }
            onDispose {
                job.cancel()
                runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().pointerInput(previewView, cameraControl) {
                detectTapGestures { position ->
                    focusPoint = position
                    val point = previewView.meteringPointFactory.createPoint(position.x, position.y)
                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    runCatching { cameraControl?.cameraControl?.startFocusAndMetering(action) }
                    scope.launch {
                        delay(900)
                        focusPoint = null
                    }
                }
            }
        )

        if (!granted) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE61A1A1A)) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("需要相机权限才能拍照扫描", color = Color.White)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("重新申请权限") }
                        TextButton(onClick = onBack) { Text("返回并使用导入图片", color = Color.White) }
                    }
                }
            }
        }

        // 三分网格，帮助把纸张拍平拍正。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.24f)
            drawLine(line, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1.dp.toPx())
            drawLine(line, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1.dp.toPx())
            drawLine(line, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1.dp.toPx())
            drawLine(line, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1.dp.toPx())
        }

        if (mode >= 3) Canvas(Modifier.fillMaxSize()) {
            val width = size.width * 0.82f
            val height = width / (85.6f / 54f)
            val left=(size.width-width)/2f; val top=(size.height-height)/2f
            drawRect(Color(0xFF90F6BE), Offset(left,top), androidx.compose.ui.geometry.Size(width,height), style=Stroke(3.dp.toPx()))
        }
        // 实时边缘框
        Canvas(modifier = Modifier.fillMaxSize()) {
            val det = detection ?: return@Canvas
            val q = det.quad
            val imgAspect = det.aspect
            val viewAspect = size.width / size.height
            val dw: Float
            val dh: Float
            if (viewAspect > imgAspect) {
                dh = size.height
                dw = dh * imgAspect
            } else {
                dw = size.width
                dh = dw / imgAspect
            }
            val ox = (size.width - dw) / 2f
            val oy = (size.height - dh) / 2f
            fun map(p: Point): Offset = Offset(ox + p.x * dw, oy + p.y * dh)

            val pts = listOf(map(q.p0), map(q.p1), map(q.p2), map(q.p3))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            drawPath(
                path = path,
                color = Color(0xFF31D158),
                style = Stroke(width = 4.dp.toPx())
            )
            pts.forEach {
                drawCircle(color = Color(0xFF31D158), radius = 10.dp.toPx(), center = it)
            }
        }

        focusPoint?.let { point ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(Color(0xFFFFD54F), 26.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                drawCircle(Color(0xFFFFD54F), 4.dp.toPx(), point)
            }
        }

        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color.White)
            }
            Row {
                TextButton(onClick = {
                    autoEdge = !autoEdge
                    if (!autoEdge) { detection = null; analyzedFrame = null; autoCapture = false }
                }) {
                    Text(if (autoEdge) "自动取边" else "整页", color = Color.White)
                }
                TextButton(onClick = {
                    autoCapture = !autoCapture
                    if (autoCapture) autoEdge = true
                    captureGate.reset()
                }) {
                    Text(if (autoCapture) "自动拍摄开" else "自动拍摄关", color = Color.White)
                }
                TextButton(onClick = {
                    torchOn = !torchOn
                    cameraControl?.cameraControl?.enableTorch(torchOn)
                }) {
                    Text(if (torchOn) "闪光开" else "闪光关", color = Color.White)
                }
            }
        }

        if (errorText != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC000000)) {
                    Text(
                        text = errorText.orEmpty(),
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (allowContinuous) Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("逐页编辑", "连续扫描", "书籍双页", "身份证", "银行卡").forEachIndexed { index, label ->
                    TextButton(onClick = { mode = index; captureGate.reset() }, enabled = !busy && !processing) {
                        Text(if (mode == index) "● $label" else label, color = if (mode == index) Color(0xFF31D158) else Color.White)
                    }
                }
            }
            if (mode >= 3) Text(if(mode == 4) "请将银行卡放入取景框，拍后检查裁切" else if(pageCount % 2 == 0) "身份证正面（人像面）· 拍后可裁切、检查和重拍" else "身份证反面（国徽面）· 完成后检查正反面", color=Color.White, style=MaterialTheme.typography.labelSmall)
            if (mode in 1..2) Text(if (mode == 2) "拍整幅书页，自动按左→右拆分；完成后逐页检查" else "拍摄后直接加入草稿；完成后检查裁边和顺序", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }

        // 底部：快门 + 完成
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("已拍 $pageCount 页", color = Color.White, style = MaterialTheme.typography.labelMedium)
                if (autoCapture) {
                    Text(
                        captureHint,
                        color = Color(0xFF31D158),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            FloatingActionButton(
                onClick = {
                    captureNow()
                },
                containerColor = Color.White,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(76.dp)
            ) {
                Text(if (busy || processing) "保存中" else "拍摄", style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = onFinish, enabled = pageCount > 0 && !busy && !processing) {
                Text("完成")
            }
        }
    }
}

private fun detect(proxy: ImageProxy, nowMs: Long): AnalyzedFrame {
    var owned: Bitmap? = null
    var analysis: Bitmap? = null
    return try {
    val raw: Bitmap = proxy.toBitmap()
    owned = raw
    val bmp = ImageIo.rotate(raw, proxy.imageInfo.rotationDegrees.toFloat())
    if (bmp !== raw) raw.recycle()
    owned = bmp
    val small = scaleBitmap(bmp, 640)
    analysis = small
    val w = small.width
    val h = small.height
    val quad = if (OpenCvDocument.available()) OpenCvDocument.detect(small, 640)
        else toGray(small).first.let { detectDocumentQuad(canny(it, w, h), w, h) }
    val aspect = w.toFloat() / h.toFloat()
    val result = if (quad == null) {
        null
    } else {
        Detection(
            Quad(
                Point(quad.p0.x / w, quad.p0.y / h),
                Point(quad.p1.x / w, quad.p1.y / h),
                Point(quad.p2.x / w, quad.p2.y / h),
                Point(quad.p3.x / w, quad.p3.y / h)
            ),
            aspect
        )
    }
    val left = quad?.toList()?.minOf { it.x.toInt() }?.coerceIn(0, w - 1) ?: 0
    val top = quad?.toList()?.minOf { it.y.toInt() }?.coerceIn(0, h - 1) ?: 0
    val right = quad?.toList()?.maxOf { it.x.toInt() }?.coerceIn(left + 1, w) ?: w
    val bottom = quad?.toList()?.maxOf { it.y.toInt() }?.coerceIn(top + 1, h) ?: h
    val crop = Bitmap.createBitmap(small, left, top, right - left, bottom - top)
    val qualityBitmap = scaleBitmap(crop, 320)
    try {
        val quality = DocumentQuality.analyze(qualityBitmap)
        AnalyzedFrame(result, quality.warnings, contentHash(qualityBitmap), nowMs)
    } finally {
        if (qualityBitmap !== crop) qualityBitmap.recycle()
        if (crop !== small) crop.recycle()
    }
} catch (e: Exception) {
    AnalyzedFrame(null, emptyList(), null, nowMs)
    } finally {
        if (analysis !== owned) analysis?.recycle()
        owned?.recycle()
    }
}

private fun contentHash(bitmap: Bitmap): Long {
    val values = IntArray(64) { i ->
        val color = bitmap.getPixel(((i % 8 + 0.5f) * bitmap.width / 8).toInt().coerceAtMost(bitmap.width - 1),
            ((i / 8 + 0.5f) * bitmap.height / 8).toInt().coerceAtMost(bitmap.height - 1))
        (((color ushr 16) and 255) * 299 + ((color ushr 8) and 255) * 587 + (color and 255) * 114) / 1000
    }
    val mean = values.average()
    return values.indices.fold(0L) { hash, i -> if (values[i] >= mean) hash or (1L shl i) else hash }
}

private fun shoot(
    capture: ImageCapture?,
    executor: java.util.concurrent.Executor,
    context: Context,
    onResult: (File?) -> Unit
) {
    val cap = capture
    if (cap == null) {
        onResult(null)
        return
    }
    cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val degrees = image.imageInfo.rotationDegrees
            val bmp = image.toBitmap()
            image.close()
            val rotated = ImageIo.rotate(bmp, degrees.toFloat())
            if (rotated !== bmp) bmp.recycle()
            val file = FileStore.newSessionFile(context)
            val ok = ImageIo.saveJpeg(rotated, file, 95)
            rotated.recycle()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(if (ok) file else null)
            }
        }

        override fun onError(exception: ImageCaptureException) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(null) }
        }
    })
}
