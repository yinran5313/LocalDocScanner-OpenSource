package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.pdf.PdfTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun FilePreview(
    files: List<File>,
    title: String,
    modifier: Modifier = Modifier
) {
    if (files.isEmpty()) return
    var selected by remember(files.map { it.absolutePath }) { mutableIntStateOf(0) }
    val safeIndex = selected.coerceIn(files.indices)
    val file = files[safeIndex]
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (files.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                files.forEachIndexed { index, item ->
                    FilterChip(
                        selected = safeIndex == index,
                        onClick = { selected = index },
                        label = { Text("${index + 1}. ${item.name.take(18)}") }
                    )
                }
            }
        }
        Text(
            "${file.name} · ${formatPreviewSize(file.length())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (file.extension.lowercase()) {
            "pdf" -> PdfFilePreview(file)
            "jpg", "jpeg", "png", "webp", "bmp" -> ZoomableImage(model = file, key = file.absolutePath)
            "txt", "csv", "json", "md", "markdown", "xml" -> TextFilePreview(file)
            else -> Box(
                Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text("该格式暂不渲染页面，可在结果区打开或分享") }
        }
    }
}

@Composable
private fun PdfFilePreview(file: File) {
    val count = remember(file, file.lastModified()) { PdfTools.pageCount(file).coerceAtLeast(0) }
    var index by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, index) {
        val next = withContext(Dispatchers.IO) { PdfTools.renderPage(file, index, 1400) }
        val old = bitmap
        bitmap = next
        old?.takeIf { it !== next }?.recycle()
    }
    DisposableEffect(file) { onDispose { bitmap?.recycle() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index-- }, enabled = index > 0) { Text("上一页") }
            Text(if (count > 0) "${index + 1} / $count" else "无法预览")
            TextButton(onClick = { index++ }, enabled = index + 1 < count) { Text("下一页") }
        }
        val value = bitmap
        if (value == null) Box(
            Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF15171A)),
            contentAlignment = Alignment.Center
        ) { Text(if (count > 0) "正在渲染预览…" else "文件可能已加密或无法读取", color = Color.White) }
        else ZoomableImage(model = value, key = "${file.absolutePath}:$index")
    }
}

@Composable
private fun ZoomableImage(model: Any, key: Any) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF15171A)),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = model,
            contentDescription = "文件预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .pointerInput(key) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                }
        )
        if (scale > 1f) TextButton(
            onClick = { scale = 1f; offset = Offset.Zero },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        ) { Text("适合屏幕") }
    }
}

@Composable
private fun TextFilePreview(file: File) {
    val text = remember(file, file.lastModified()) {
        runCatching {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(12000)
                val count = reader.read(chars)
                if (count <= 0) "" else String(chars, 0, count) + if (file.length() > 12000) "\n……" else ""
            }
        }.getOrElse { "无法读取文字：${it.message}" }
    }
    Box(
        Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text.ifBlank { "文件没有文字" }, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        }
    }
}

private fun formatPreviewSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
