package com.localdoc.scanner.ui.tools

import android.graphics.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.ReviewPage
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class SourceFocus(val label: String, val value: String, val row: Int? = null, val column: Int? = null)

@Composable
internal fun SourceLocatorDialog(page: ReviewPage, focus: SourceFocus, onClose: () -> Unit) {
    var bitmap by remember(page.source, page.page, focus) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("正在定位原图…") }
    LaunchedEffect(page.source, page.page, focus) {
        try {
            val located = withContext(Dispatchers.IO) {
                val file = File(page.source)
                val input = if (file.extension.equals("pdf", true)) PdfTools.renderPage(file, page.page, 1600) else ImageIo.loadFromFile(file, 1600)
                requireNotNull(input) { "原文件无法读取" }
                val image = try { input.copy(android.graphics.Bitmap.Config.ARGB_8888, true) } finally { input.recycle() }
                val cell = page.modelCells?.firstOrNull { focus.row == it.row && focus.column == it.column }
                val boxes = if (cell != null) listOf(OcrTextBox(focus.value, cell.left, cell.top, cell.right, cell.bottom))
                    else page.sourceBoxes.orEmpty().filter { b -> focus.value.trim().takeIf(String::isNotBlank)?.let { it in b.text || b.text.isNotBlank() && b.text in it } == true }
                val paint = Paint().apply { color = Color.rgb(255, 87, 34); style = Paint.Style.STROKE; strokeWidth = image.width / 250f }
                val canvas = Canvas(image)
                boxes.filter { b -> listOf(b.left,b.top,b.right,b.bottom).all(Float::isFinite) && b.right>b.left && b.bottom>b.top }.forEach { b -> canvas.drawRect(b.left.coerceIn(0f,1f)*image.width, b.top.coerceIn(0f,1f)*image.height, b.right.coerceIn(0f,1f)*image.width, b.bottom.coerceIn(0f,1f)*image.height, paint) }
                image to if (boxes.isEmpty()) "没有匹配位置，显示完整原图供人工核对" else "橙色框为来源位置；双指可放大并拖动"
            }
            bitmap = located.first; status = located.second
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = "定位失败：${e.message}" }
    }
    DisposableEffect(bitmap) { val image = bitmap; onDispose { image?.recycle() } }
    AlertDialog(onDismissRequest = onClose, title = { Text("原图 · ${focus.label}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(focus.value); Text(status, style = MaterialTheme.typography.bodySmall)
            bitmap?.let { ZoomableImage(it, it, Modifier.fillMaxWidth().height(360.dp)) }
        }
    }, confirmButton = { TextButton(onClose) { Text("返回复核") } })
}
