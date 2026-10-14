package com.localdoc.scanner.barcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.localdoc.scanner.ui.components.ScannerTopBar
import java.util.concurrent.Executors

@Composable
fun LiveBarcodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var history by remember { mutableStateOf(BarcodeHistory.all(context)) }
    var selected by remember { mutableStateOf<BarcodeRecord?>(null) }
    var clear by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val paused by rememberUpdatedState(selected != null)
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    LaunchedEffect(Unit) { if (!permission) ask.launch(Manifest.permission.CAMERA) }
    DisposableEffect(permission, owner) {
        var disposed = false; var provider: ProcessCameraProvider? = null
        val executor = Executors.newSingleThreadExecutor()
        val display = Preview.Builder().build().apply { setSurfaceProvider(preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        if (permission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ if (!disposed) try {
                provider = future.get(); val reader = MultiFormatReader(); var last = 0L
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        if (paused || android.os.SystemClock.elapsedRealtime() - last < 350) return@setAnalyzer
                        last = android.os.SystemClock.elapsedRealtime()
                        val plane = proxy.planes[0]; val buffer = plane.buffer.duplicate(); val w = proxy.width; val h = proxy.height
                        val data = ByteArray(w * h)
                        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                        val found = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)))) }.getOrNull()
                            ?: run { val rotated = ByteArray(data.size)
                                for (y in 0 until h) for (x in 0 until w) rotated[x * h + h - 1 - y] = data[y * w + x]
                                runCatching { reader.decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(rotated, h, w, 0, 0, h, w, false)))) }.getOrNull() }
                        reader.reset()
                        if (found != null) ContextCompat.getMainExecutor(context).execute { if (!disposed && selected == null) {
                            BarcodeHistory.add(context, found.text, found.barcodeFormat.name); history = BarcodeHistory.all(context); selected = history.firstOrNull()
                        } }
                    } catch (_: Exception) { /* A non-decodable frame does not interrupt scanning. */ }
                    finally { proxy.close() }
                }
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, display, analysis)
            } catch (_: Exception) { error = "相机暂不可用，请返回后重试，或使用图片识别" } }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true; analysis.clearAnalyzer(); provider?.unbind(display, analysis); executor.shutdownNow() }
    }
    Scaffold(topBar = { ScannerTopBar("扫码", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (permission) AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().weight(1f))
            else Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }
            Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(error.ifBlank { "对准二维码或条形码，识别成功会暂停；链接由你选择是否打开。" }, style = MaterialTheme.typography.bodySmall)
                Row { Text("最近扫码 · ${history.size}条", Modifier.weight(1f)); TextButton({ clear = true }, enabled = history.isNotEmpty()) { Text("清空历史") } }
                history.forEach { record -> TextButton({ selected = record }) { Text(record.text.take(100), maxLines = 2) } }
            }
        }
    }
    selected?.let { record -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("扫码结果") }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(record.text) }
    }, confirmButton = { TextButton({ selected = null }) { Text("继续扫描") } }, dismissButton = { Column {
        val uri = runCatching { Uri.parse(record.text.trim()) }.getOrNull()
        if (uri?.scheme?.lowercase() in listOf("https", "http") && !uri?.host.isNullOrBlank()) TextButton({
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { error = "没有可打开此链接的浏览器" }
        }) { Text("在浏览器打开") }
        TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, record.text) }, "分享扫码结果")) }) { Text("分享文字") }
    } }) }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空扫码历史？") }, confirmButton = { TextButton({ BarcodeHistory.clear(context); history = emptyList(); clear = false }) { Text("清空") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}
