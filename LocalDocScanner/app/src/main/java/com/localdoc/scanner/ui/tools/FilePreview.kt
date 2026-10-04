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
    modifier: Modifier = Modifier,
    pdfInitialPage: Int = 0
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
            "pdf" -> PdfReader(file, Modifier.fillMaxWidth().height(560.dp), initialPage = pdfInitialPage)
            "jpg", "jpeg", "png", "webp", "bmp" -> ZoomableImage(model = file, key = file.absolutePath)
            "txt", "csv", "json", "md", "markdown", "xml" -> TextFilePreview(file)
            "docx", "xlsx", "pptx" -> OpenXmlFilePreview(file)
            else -> Box(
                Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text("该格式暂不渲染页面，可在结果区打开或分享") }
        }
    }
}

@Composable
private fun OpenXmlFilePreview(file: File) {
    var preview by remember(file, file.lastModified()) { mutableStateOf("正在读取内容…") }
    LaunchedEffect(file, file.lastModified()) {
        preview = withContext(Dispatchers.IO) { runCatching {
            com.localdoc.scanner.office.OpenXmlEditor.read(file).units.joinToString("\n") { "${it.section} · ${it.label}：${it.text}" }
        }.getOrElse { "内容预览失败：${it.message}" } }
    }
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(preview, modifier = Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState()).padding(8.dp))
    }
    Text("此处显示文字和单元格内容；完整排版可点打开进入内置Office。", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ZoomableImage(model: Any, key: Any, modifier: Modifier = Modifier.fillMaxWidth().height(300.dp)) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    Box(
        modifier.background(Color(0xFF15171A)),
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
    var text by remember(file) { mutableStateOf("读取中…") }
    LaunchedEffect(file, file.lastModified()) {
        text = withContext(Dispatchers.IO) { runCatching {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(12000)
                val count = reader.read(chars)
                if (count <= 0) "" else String(chars, 0, count) + if (file.length() > 12000) "\n……" else ""
            }
        }.getOrElse { "无法读取文字：${it.message}" } }
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
