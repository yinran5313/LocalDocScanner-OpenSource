package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.DocumentLayouts
import com.localdoc.scanner.cv.BookDewarp
import com.localdoc.scanner.cv.Stitch
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.localdoc.scanner.ui.components.*

@Composable
internal fun ImagesToPdfFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val defaults = com.localdoc.scanner.data.AppPreferences(androidx.compose.ui.platform.LocalContext.current)
    var paper by rememberToolState(request, "paperV5") { defaults.pageSize.name }

    var quality by rememberToolState(request, "qualityV5") { if(defaults.imageSide==3200) 0 else 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("页面尺寸", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("A4", "Letter", "原尺寸"), com.localdoc.scanner.export.PdfExporter.PageSize.valueOf(paper).ordinal) { paper = com.localdoc.scanner.export.PdfExporter.PageSize.entries[it].name }
            Text("清晰度", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("高", "中", "低"), quality) { quality = it }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "生成 PDF", modifier = modifier, config = { panel() }
    )
}



@Composable
internal fun LongImageFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var width by rememberToolState(request, "width") { 0 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("拼接宽度", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("1080", "720", "480"), width) { width = it }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "拼接", modifier = modifier, config = { panel() }
    )
}



@Composable
internal fun ImageEditFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var filter by rememberToolState(request, "filter") { ScanFilter.AUTO }
    var rotation by rememberToolState(request, "rotation") { 0 }
    var mode by rememberToolState(request, "mode") { 0 }
    var order by rememberToolState(request, "imageOrder") { request.files.toList() }
    var selected by rememberToolState(request, "imageSelected") { 0 }
    var splits by rememberToolState<Map<String, Float>>(request, "bookSplits") { emptyMap() }
    var flattenBook by rememberToolState(request, "flattenBook") { false }
    var deskew by rememberToolState(request, "autoDeskew") { false }
    var rightFirst by rememberToolState(request, "bookRightFirst") { false }
    var makePdf by rememberToolState(request, "imageBatchPdf") { true }
    var previewNote by remember { mutableStateOf("") }
    var previewError by remember { mutableStateOf("") }
    var livePreview by remember { mutableStateOf<Bitmap?>(null) }
    val selectedIndex = selected.coerceIn(order.indices)
    val selectedFile = order[selectedIndex]
    val split = splits[selectedFile.absolutePath] ?: .5f
    LaunchedEffect(selectedFile, mode) {
        if (mode == 1 && selectedFile.absolutePath !in splits) {
            val hint = withContext(Dispatchers.Default) {
                ImageIo.loadFromFile(selectedFile, 640)?.let { image ->
                    try { com.localdoc.scanner.cv.BookGutter.estimate(image) } finally { image.recycle() }
                }
            }
            splits = splits + (selectedFile.absolutePath to (hint?.ratio ?: .5f))
        }
    }
    LaunchedEffect(selectedFile, order, filter, rotation, mode, split, flattenBook, deskew, rightFirst) {
        kotlinx.coroutines.delay(200)
        var pending: Bitmap? = null
        previewError = ""
        try {
            var note = ""
            withContext(Dispatchers.Default) {
                val owned = mutableListOf<Bitmap>()
                fun transformed(source: Bitmap): Bitmap {
                    val result = com.localdoc.scanner.cv.ImageBatchProcessor.render(source, rotation, deskew, filter)
                    owned.add(result.bitmap)
                    if (result.note.isNotBlank()) note += result.note + "\n"
                    return result.bitmap
                }
                try {
                    pending = when (mode) {
                        1 -> {
                            val source = ImageIo.loadFromFile(selectedFile, if (flattenBook) 4000 else 1400)
                                ?: error("图片读取失败")
                            owned.add(source)
                            val pair = DocumentLayouts.splitBookSpread(source, splitRatio = split)
                            owned.addAll(listOf(pair.first, pair.second))
                            var pages = listOf(pair.first, pair.second).map { page ->
                                if (flattenBook) BookDewarp.flatten(page).let { result ->
                                    owned.add(result.bitmap); note += result.note + "\n"; result.bitmap
                                } else page
                            }.map(::transformed)
                            if (rightFirst) pages = pages.reversed()
                            Stitch.vertical(pages, 900) ?: error("无法生成双页预览")
                        }
                        2 -> {
                            require(order.size == 2) { "证件拼版请只选择正反面两张图" }
                            val pages = order.map { file ->
                                (ImageIo.loadFromFile(file, 1200) ?: error("图片读取失败")).also { owned.add(it) }
                            }.map(::transformed)
                            DocumentLayouts.idCardSheet(pages[0], pages[1])
                        }
                        else -> {
                            val source = (ImageIo.loadFromFile(selectedFile, 1400) ?: error("图片读取失败")).also { owned.add(it) }
                            transformed(source)
                        }
                    }
                } finally { owned.distinct().filter { it !== pending }.forEach { it.recycle() } }
            }
            livePreview?.takeIf { it !== pending }?.recycle()
            livePreview = pending; pending = null
            previewNote = note.trim()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            livePreview?.recycle(); livePreview = null
            previewError = e.message ?: "无法生成预览"
        } finally { pending?.recycle() }
    }
    val currentPreview by rememberUpdatedState(livePreview)
    DisposableEffect(Unit) { onDispose { currentPreview?.recycle() } }

    fun moveSelected(delta: Int) {
        val next = selectedIndex + delta
        if (next !in order.indices) return
        order = order.toMutableList().apply { add(next, removeAt(selectedIndex)) }
        selected = next
    }
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, runLabel = "处理并生成文件", showInputPreview = false,
        config = {
            Text("处理方式", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("编辑图片", "书本双页拆分", "证件双面拼版"), mode) { mode = it }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                order.forEachIndexed { index, file ->
                    FilterChip(selectedIndex == index, { selected = index }, label = {
                        Text("${index + 1}. ${(request.names.getOrNull(request.files.indexOf(file)) ?: file.name).take(18)}")
                    })
                }
            }
            Row {
                TextButton(onClick = { moveSelected(-1) }, enabled = selectedIndex > 0) { Text("向前移") }
                TextButton(onClick = { moveSelected(1) }, enabled = selectedIndex < order.lastIndex) { Text("向后移") }
            }
            Text("共${order.size}张；旋转、滤镜与纠偏应用于全部图片。请逐张检查预览。", style = MaterialTheme.typography.bodySmall)
            if (mode == 1) {
                Text("当前第${selectedIndex + 1}张书缝：左 ${(split * 100).toInt()}% / 右 ${((1 - split) * 100).toInt()}%")
                Slider(split, { splits = splits + (selectedFile.absolutePath to it) }, valueRange = .2f.. .8f)
                Text("书缝位置按每张图分别保存；未预览的图片自动估计书缝。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("按文字行曲率展平", Modifier.weight(1f)); Switch(flattenBook, { flattenBook = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("右页在前", Modifier.weight(1f)); Switch(rightFirst, { rightFirst = it }) }
            }
            if (mode == 2 && order.size != 2) Text("证件拼版必须正好两张图。", color = MaterialTheme.colorScheme.error)
            Text("滤镜", style = MaterialTheme.typography.titleSmall)
            ChipRow(ScanFilter.entries.map { it.label }, filter.ordinal) { filter = ScanFilter.entries[it] }
            ChipRow(listOf("0°", "90°", "180°", "270°"), rotation) { rotation = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("自动纠偏（保留完整边缘）", Modifier.weight(1f)); Switch(deskew, { deskew = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("同时生成按序合并PDF", Modifier.weight(1f)); Switch(makePdf, { makePdf = it }) }
            Text("实时处理预览", style = MaterialTheme.typography.titleSmall)
            if (previewError.isNotBlank()) Text(previewError, color = MaterialTheme.colorScheme.error)
            else livePreview?.let { ZoomableImage(it, "${selectedFile.path}:$mode:$split:$rotation:$filter", Modifier.fillMaxWidth().height(360.dp)) }
                ?: Text("正在生成预览…")
            if (previewNote.isNotBlank()) Text(previewNote, style = MaterialTheme.typography.bodySmall)
        }
    )
}
