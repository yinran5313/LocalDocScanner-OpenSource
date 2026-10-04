package com.localdoc.scanner.ui.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.ocr.PaddleOcrEngine
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.structure.*
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.ImageIo
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ReviewPage(val source: String, val page: Int, val raw: String,
    val fields: Map<String, String>, val rows: List<List<String>>, val error: String = "", val reviewed: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StructuredWorkbench(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val tableOnly = request.tool.id == "table_xlsx"
    var kind by rememberToolState(request, "batchKind") { 0 }
    var template by rememberToolState(request, "template") { "编号=(?:号码|编号)[：:\\s]*(\\S+)" }
    var pages by rememberToolState<List<ReviewPage>>(request, "reviewPages") { emptyList() }
    var selected by rememberToolState(request, "selectedPage") { 0 }
    var rowPage by rememberToolState(request, "rowPage") { 0 }
    var status by rememberToolState(request, "batchStatus") { "" }
    var exported by rememberToolState<File?>(request, "batchExport") { null }
    var precise by rememberToolState(request, "batchMedium") { true }
    var busy by rememberToolState(request, "batchBusy") { false }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request) { if (!vm.isToolRunning) busy = false }
    fun extract(raw: String): Map<String, String> = when (kind) {
        0 -> StructureExtractor.extract(StructureExtractor.Kind.INVOICE, raw).fields.associate { it.label to it.value }
        1 -> ReceiptExtractor.fields(raw)
        else -> ReceiptExtractor.custom(raw, template)
    }
    fun replace(page: ReviewPage) { pages = pages.mapIndexed { i, old -> if (i == selected) page.copy(reviewed = false) else old }; exported = null }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val file = exported
        if (uri != null && file != null) scope.launch {
            status = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法创建目标文件")
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val label = OutputHistoryStore.describeDestination(context, uri)
                OutputHistoryStore.markSaved(context, file, uri, label)
                "已保存：$label"
            }.getOrElse { "保存失败：${it.message}" } }
        }
    }
    fun recognize() {
        if (vm.isToolRunning) return
        busy = true
        exported = null
        pages = emptyList()
        vm.runTool {
            val engine = PaddleOcrEngine(context)
            try {
                for (source in request.files) {
                    val count = if (source.extension.equals("pdf", true)) PdfTools.pageCount(source) else 1
                    if (count <= 0) { pages = pages + ReviewPage(source.absolutePath, 0, "", emptyMap(), emptyList(), "无法读取PDF，可能需要先解锁"); continue }
                    for (index in 0 until count) {
                        status = "识别 ${source.name} 第${index + 1}/${count}页"
                        val reviewed = withContext(Dispatchers.IO) { runCatching {
                            val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, index, 3000) else ImageIo.loadFromFile(source, 3600)
                            requireNotNull(bitmap) { "无法读取页面图片" }
                            try {
                                val outcome = engine.recognize(bitmap, precise)
                                val rows = TableRecovery.fromBoxes(outcome.boxes).ifEmpty { TableRecovery.fromText(outcome.text) }
                                ReviewPage(source.absolutePath, index, outcome.text, if (tableOnly) emptyMap() else extract(outcome.text), rows,
                                    if (outcome.text.isBlank()) "未识别到文字，请重试或录入" else "")
                            } finally { bitmap.recycle() }
                        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; ReviewPage(source.absolutePath, index, "", emptyMap(), emptyList(), it.message ?: "识别失败") } }
                        pages = pages + reviewed
                    }
                }
                status = "识别结束：${pages.size}页，${pages.count { it.error.isNotBlank() }}页需处理。请逐页确认字段和表格，导出保留原文。"
            } finally { engine.close(); busy = false }
        }
    }
    fun export() {
        if (vm.isToolRunning || pages.isEmpty()) return
        busy = true
        vm.runTool {
            val result = withContext(Dispatchers.IO) { runCatching {
                val sheets = mutableListOf<Pair<String, List<List<String>>>>()
                if (!tableOnly) {
                    val keys = pages.flatMap { it.fields.keys }.distinct()
                    sheets += "汇总" to (listOf(listOf("来源", "页", "复核", "错误") + keys) + pages.map {
                        listOf(File(it.source).name, (it.page + 1).toString(), if (it.reviewed) "已确认" else "待复核", it.error) + keys.map { key -> it.fields[key].orEmpty() }
                    })
                }
                pages.forEachIndexed { i, page -> if (page.rows.isNotEmpty()) sheets += "明细${i + 1}" to page.rows }
                sheets += "原始识别" to (listOf(listOf("来源", "页", "原文分段", "错误")) + pages.flatMap { page ->
                    page.raw.chunked(32000).ifEmpty { listOf("") }.map { listOf(File(page.source).name, (page.page + 1).toString(), it, page.error) }
                })
                val output = File(FileStore.exportDir(context), "${if (tableOnly) "表格" else "票据汇总"}_${System.currentTimeMillis()}.xlsx")
                SpreadsheetExport.write(output, sheets)
                OutputHistoryStore.recordGenerated(context, output, OutputHistoryStore.mimeFor(output))
                output
            } }
            result.onSuccess { exported = it; status = "已生成XLSX；${pages.count { !it.reviewed }}页尚未确认，状态及原文已保留。请选择保存位置。" }
                .onFailure { status = "导出失败：${it.message}" }
            busy = false
        }
    }
    Scaffold(modifier, topBar = { TopAppBar(title = { Text(request.tool.label) }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("表格行列和票据字段为识别建议，请对照原图复核。合并单元格和复杂版面需要手工调整。")
            if (!tableOnly) Row {
                listOf("发票", "小票/收据", "自定义").forEachIndexed { i, name -> FilterChip(kind == i, { kind = i }, label = { Text(name) }) }
            }
            if (kind == 2 && !tableOnly) OutlinedTextField(template, { template = it }, label = { Text("一行一个字段名=正则，第一捕获组为值") }, modifier = Modifier.fillMaxWidth())
            Row { Switch(precise, { precise = it }); Text("高精度 medium") }
            Button(onClick = ::recognize, enabled = !busy && !vm.isToolRunning) { Text(if (pages.isEmpty()) "识别全部文件" else "重新识别全部") }
            if (status.isNotBlank()) Text(status)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (pages.isNotEmpty()) {
                selected = selected.coerceIn(0, pages.lastIndex)
                val current = pages[selected]
                Row {
                    TextButton(onClick = { selected--; rowPage = 0 }, enabled = selected > 0) { Text("上一份") }
                    Text("${selected + 1}/${pages.size} · 第${current.page + 1}页")
                    TextButton(onClick = { selected++; rowPage = 0 }, enabled = selected < pages.lastIndex) { Text("下一份") }
                }
                FilePreview(listOf(File(current.source)), "原文件 ${File(current.source).name} · 第${current.page + 1}页", pdfInitialPage = current.page)
                if (current.error.isNotBlank()) Text(current.error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(current.raw, { replace(current.copy(raw = it, error = "")) }, label = { Text("识别原文，可修正后重新提取") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                if (!tableOnly) {
                    TextButton(onClick = { runCatching { extract(current.raw) }.onSuccess { replace(current.copy(fields = it)) }.onFailure { status = "模板错误：${it.message}" } }) { Text("按当前类型重新提取字段") }
                    current.fields.forEach { (key, value) -> OutlinedTextField(value, { replace(current.copy(fields = current.fields + (key to it))) }, label = { Text(key) }, modifier = Modifier.fillMaxWidth()) }
                }
                Text("表格/商品明细 · ${current.rows.size}行，可直接改格子")
                val rowStart = rowPage.coerceAtMost((current.rows.size - 1).coerceAtLeast(0) / 10) * 10
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    current.rows.drop(rowStart).take(10).forEachIndexed { offset, row -> Row {
                        row.forEachIndexed { column, cell -> OutlinedTextField(cell, { value ->
                            val rows = current.rows.mapIndexed { r, old -> if (r == rowStart + offset) old.mapIndexed { c, oldCell -> if (c == column) value else oldCell } else old }
                            replace(current.copy(rows = rows))
                        }, label = { Text("${rowStart + offset + 1}:${column + 1}") }, modifier = Modifier.width(130.dp)) }
                        TextButton(onClick = { replace(current.copy(rows = current.rows.filterIndexed { r, _ -> r != rowStart + offset })) }) { Text("删行") }
                    } }
                }
                Row {
                    TextButton(onClick = { rowPage-- }, enabled = rowStart > 0) { Text("前10行") }
                    TextButton(onClick = { rowPage++ }, enabled = rowStart + 10 < current.rows.size) { Text("后10行") }
                    TextButton(onClick = { replace(current.copy(rows = current.rows + listOf(List(current.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1) { "" }))) }) { Text("加行") }
                    TextButton(onClick = { replace(current.copy(rows = current.rows.ifEmpty { listOf(emptyList()) }.map { it + "" })) }) { Text("加列") }
                }
                Button(onClick = { pages = pages.mapIndexed { i, p -> if (i == selected) p.copy(reviewed = true) else p } }) { Text(if (current.reviewed) "此页已确认" else "确认此页已复核") }
                Button(onClick = ::export, enabled = !busy && !vm.isToolRunning) { Text("生成XLSX汇总与明细") }
            } else FilePreview(request.files, "输入文件")
            exported?.takeIf(File::isFile)?.let { file ->
                FilePreview(listOf(file), "导出预览")
                Row {
                    Button(onClick = { save.launch(file.name) }) { Text("保存到手机") }
                    TextButton(onClick = { Share.file(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("分享") }
                    TextButton(onClick = { Share.open(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("完整表格") }
                }
            }
        }
    }
}
