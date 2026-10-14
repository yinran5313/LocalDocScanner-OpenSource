package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PdfPagesFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val source = request.files.first()
    var order by rememberToolState(request, "pdfPageOrder") { emptyList<Int>() }
    var turns by rememberToolState(request, "pdfPageTurns") { emptyMap<Int, Int>() }
    var initialized by rememberToolState(request, "pagesInitialized") { false }
    var selected by rememberToolState(request, "pageExportRange") { "" }
    var removed by rememberToolState(request, "removedPages") { emptyList<Int>() }
    var count by remember(request) { mutableIntStateOf(0) }
    LaunchedEffect(request) { count = withContext(Dispatchers.IO) { PdfTools.pageCount(source) }; if (!initialized && count > 0) { order = (0 until count).toList(); initialized = true } }
    val latestOrder by rememberUpdatedState(order); val cardWidth = with(LocalDensity.current) { 138.dp.toPx() }
    val cache = remember(source) { File(context.cacheDir, "pdf-page-thumbs/${source.absolutePath.hashCode()}-${source.lastModified()}").apply { mkdirs() } }
    fun move(from: Int, to: Int) { if (from in order.indices && to in order.indices && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) } }
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, config = {
        Text("长按缩略图后左右拖动排序，也可以用箭头移动。删除、旋转只应用于生成的副本，原PDF保留。", style = MaterialTheme.typography.bodySmall)
        Text("保留${order.size}/$count 页")
        LazyRow(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 270.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(order, key = { _, page -> page }) { index, page ->
                var thumb by remember(source, page) { mutableStateOf<File?>(null) }
                LaunchedEffect(source, page) {
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                        val target = File(cache, "$page.jpg")
                        if (!target.isFile) { val bitmap = PdfTools.renderPage(source, page, 300)
                            if (bitmap != null) try { com.localdoc.scanner.util.AtomicFiles.write(target) { check(com.localdoc.scanner.util.ImageIo.saveJpeg(bitmap, it, 85)) } } finally { bitmap.recycle() }
                        }
                        target.takeIf(File::isFile)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                    }
                    thumb = loaded
                }
                Card(Modifier.width(128.dp).pointerInput(page, cardWidth) {
                    var delta = 0f
                    detectDragGesturesAfterLongPress(onDragStart = { delta = 0f }, onDragCancel = { delta = 0f }, onDragEnd = {
                        val from = latestOrder.indexOf(page); val to = (from + (delta / cardWidth).toInt()).coerceIn(0, latestOrder.lastIndex.coerceAtLeast(0)); move(from, to)
                    }) { change, amount -> change.consume(); delta += amount.x }
                }) {
                    Column(Modifier.padding(6.dp)) {
                        thumb?.let { AsyncImage(it, "原第${page + 1}页", Modifier.fillMaxWidth().height(125.dp).rotate((turns[page] ?: 0) * 90f)) }
                        Text("${index + 1} · 原第${page + 1}页", style = MaterialTheme.typography.labelSmall)
                        Row { TextButton({ move(index, index - 1) }, enabled = index > 0, contentPadding = PaddingValues(4.dp)) { Text("←") }
                            TextButton({ move(index, index + 1) }, enabled = index < order.lastIndex, contentPadding = PaddingValues(4.dp)) { Text("→") } }
                        Row { TextButton({ turns = turns + (page to (((turns[page] ?: 0) + 1) % 4)) }, contentPadding = PaddingValues(4.dp)) { Text("旋转") }
                            TextButton({ order = order - page; removed = removed + page }, contentPadding = PaddingValues(4.dp)) { Text("删除") } }
                    }
                }
            }
        }
        if (removed.isNotEmpty()) TextButton({ order = order + removed; removed = emptyList() }) { Text("恢复已移除的${removed.size}页") }
        OutlinedTextField(selected, { selected = it }, label = { Text("仅导出原页码，如1-3,5；留空为全部保留页") }, modifier = Modifier.fillMaxWidth())
    })
}
