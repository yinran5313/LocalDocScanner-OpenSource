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
import com.localdoc.scanner.ui.components.ScannerTopBar
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.ToolDrafts
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StructuredWorkbench(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val tableOnly = request.tool.id == "table_xlsx"
    var combinePages by rememberToolState(request, "combinePages") { false }
    var skipHeaders by rememberToolState(request, "skipHeaders") { true }
    var kind by rememberToolState(request, "batchKind") { 0 }
    var template by rememberToolState(request, "template") { "编号=(?:号码|编号)[：:\\s]*(\\S+)" }
    var pages by rememberToolState<List<ReviewPage>>(request, "reviewPages") { emptyList() }
    var selected by rememberToolState(request, "selectedPage") { 0 }
    var rowPage by rememberToolState(request, "rowPage") { 0 }
    var status by rememberToolState(request, "batchStatus") { "" }
    var exported by rememberToolState<File?>(request, "batchExport") { null }
    var language by rememberToolState(request, "ocrLanguage") { "AUTO" }
    var precise by rememberToolState(request, "batchMedium") { true }
    var submitting by remember { mutableStateOf(false) }
    var recognizeId by remember(request) { mutableStateOf(ToolTasks.latest(context, request)) }
    var exportId by remember(request) { mutableStateOf(ToolTasks.latest(context, request, "export")) }
    val tasks by remember { ToolTasks.watch(context) }.collectAsState(emptyList())
    val recognition = tasks.firstOrNull { it.id == recognizeId }
    val exportTask = tasks.firstOrNull { it.id == exportId }
    val busy = submitting || listOfNotNull(recognition, exportTask).any { it.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING) }
    var applied by rememberToolState(request, "batchAppliedTask") { "" }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recognition?.updatedAt, exportTask?.updatedAt) {
        val done = setOf(ToolTaskState.SUCCEEDED, ToolTaskState.PARTIAL)
        if (recognition?.state in done) {
            val token = "${recognition!!.id}:${recognition.updatedAt}"
            if (applied != token) {
                val outcome = withContext(Dispatchers.IO) { ToolTasks.outcome(context, recognition.id) }
                if (outcome != null) {
                    val previous = pages.associateBy { it.source to it.page }
                    pages = outcome.reviewPages.map { fresh -> previous[fresh.source to fresh.page]?.takeIf { it.reviewed || it.edited } ?: fresh }
                    status = "识别结束：${pages.size}页，请逐页确认字段及表格。"
                    applied = token
                }
            }
        }
        if (exportTask?.state == ToolTaskState.SUCCEEDED) {
            exported = withContext(Dispatchers.IO) { ToolTasks.outcome(context, exportTask!!.id)?.files?.firstOrNull() }
            status = "已生成XLSX，复核状态与原文已保留，请保存到手机。"
        }
    }
    fun extract(raw: String): Map<String, String> = when (kind) {
        0 -> StructureExtractor.extract(StructureExtractor.Kind.INVOICE, raw).fields.associate { it.label to it.value }
        1 -> ReceiptExtractor.fields(raw)
        else -> ReceiptExtractor.custom(raw, template)
    }
    fun replace(page: ReviewPage) { pages = pages.mapIndexed { i, old -> if (i == selected) page.copy(reviewed = false, edited = true, merges = if (page.rows.size != old.rows.size || page.rows.maxOfOrNull { it.size } != old.rows.maxOfOrNull { it.size }) emptyList() else page.merges) else old }; exported = null }
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
        if (busy) return
        val parameters = ToolDrafts.snapshot(context, request)
        submitting = true
        scope.launch {
            try { recognizeId = ToolTasks.submit(context, request, parameters); pages = emptyList(); exported = null }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = "提交失败：${e.message}" }
            finally { submitting = false }
        }
    }
    fun export() {
        if (busy || pages.isEmpty()) return
        val parameters = mapOf("reviewPages" to ToolDrafts.gson.toJson(pages), "combinePages" to ToolDrafts.gson.toJson(combinePages), "skipHeaders" to ToolDrafts.gson.toJson(skipHeaders))
        submitting = true
        scope.launch {
            try { exportId = ToolTasks.submit(context, request, parameters, action = "export") }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = "导出提交失败：${e.message}" }
            finally { submitting = false }
        }
    }
    Scaffold(modifier, topBar = { ScannerTopBar(request.tool.label, onBack, "识别与人工复核") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("表格行列和票据字段为识别建议，请对照原图复核。合并单元格和复杂版面需要手工调整。")
            if (!tableOnly) Row {
                listOf("发票", "小票/收据", "自定义").forEachIndexed { i, name -> FilterChip(kind == i, { kind = i }, label = { Text(name) }) }
            }
            if (kind == 2 && !tableOnly) OutlinedTextField(template, { template = it }, label = { Text("一行一个字段名=正则，第一捕获组为值") }, modifier = Modifier.fillMaxWidth())
            OcrLanguagePicker(language) { language = it }
            Row { Switch(precise, { precise = it }); Text("高精度 medium") }
            Button(onClick = ::recognize, enabled = !busy) { Text(if (pages.isEmpty()) "识别全部文件" else "重新识别全部") }
            listOfNotNull(recognition, exportTask).forEach { task ->
                ToolTaskPanel(task, onPause = { scope.launch { ToolTasks.pause(context, task.id) } }, onResume = { scope.launch {
                    try { ToolTasks.resume(context, task.id) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() }
                } })
            }
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
                TableReviewOptions(current) { replace(it) }
                Row { Switch(combinePages, { combinePages = it; exported = null }); Text("将同列数的跨页表格合并为一张表") }
                if(combinePages) Row { Switch(skipHeaders, { skipHeaders = it; exported = null }); Text("移除完全相同的重复表头") }
                Button(onClick = { pages = pages.mapIndexed { i, p -> if (i == selected) p.copy(reviewed = true) else p } }) { Text(if (current.reviewed) "此页已确认" else "确认此页已复核") }
                Button(onClick = ::export, enabled = !busy) { Text("生成XLSX汇总与明细") }
            } else FilePreview(request.files, "输入文件")
            exported?.takeIf(File::isFile)?.let { file ->
                FilePreview(listOf(file), "导出预览")
                com.localdoc.scanner.ui.components.SaveDefaultButton(listOf(file)) { status = it }
                Row {
                    Button(onClick = { save.launch(file.name) }) { Text("保存到手机") }
                    TextButton(onClick = { Share.file(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("分享") }
                    TextButton(onClick = { Share.open(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("完整表格") }
                }
            }
        }
    }
}
