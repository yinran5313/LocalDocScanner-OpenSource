package com.localdoc.scanner.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.localdoc.scanner.barcode.BarcodeDecoder
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.DocumentLayouts
import com.localdoc.scanner.cv.BookDewarp
import com.localdoc.scanner.cv.Stitch
import com.localdoc.scanner.cv.applyFilter
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.ocr.PaddleOcrEngine
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.pdf.PdfInkPoint
import com.localdoc.scanner.pdf.PdfMarkup
import com.localdoc.scanner.pdf.PdfOfficeTools
import com.localdoc.scanner.structure.StructureExtractor
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.ImageIo
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class FlowOutcome(
    val summary: List<Pair<String, String>>,
    val files: List<File>,
    val docFiles: List<File> = emptyList(),
    val copyText: String? = null
)

private data class PdfPlacement(val x: Float, val y: Float, val width: Float, val height: Float)

private data class PendingPdfEdit(
    val operation: Int,
    val label: String,
    val pageIndex: Int,
    val text: String,
    val header: String,
    val footer: String,
    val watermark: String,
    val addPageNumbers: Boolean,
    val opacity: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val markup: PdfMarkup,
    val strokes: List<List<PdfInkPoint>>,
    val signatureUri: Uri?,
    val watermarkUri: Uri?,
    val formValues: Map<String, String>,
    val annotationChange: com.localdoc.scanner.pdf.PdfAnnotationChange? = null,
    val decoration: com.localdoc.scanner.pdf.PdfDecorationOptions? = null,
    val textChange: com.localdoc.scanner.pdf.PdfTextChange? = null
)

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

private fun stamp(): String = SimpleDateFormat("MMdd_HHmmss_SSS", Locale.getDefault()).format(Date())

// ======================================================================
// 入口分发
// ======================================================================

@Composable
fun ToolScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val request = vm.toolRequest
    if (request == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有待处理的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val inputKey = request.files.map { it.absolutePath }
    var checked by remember(inputKey) { mutableStateOf(false) }
    var locked by remember(inputKey) { mutableStateOf<File?>(null) }
    var inputError by remember(inputKey) { mutableStateOf("") }
    LaunchedEffect(inputKey) {
        withContext(Dispatchers.IO) {
            for (file in request.files.filter { it.extension.equals("pdf", true) }) {
                try {
                    val encrypted = com.localdoc.scanner.pdf.PdfReadSession.open(file).use { it.encrypted }
                    if (encrypted) { locked = file; break }
                } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                    locked = file; break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    inputError = "${request.names.getOrNull(request.files.indexOf(file)) ?: file.name}：${e.message}"
                    break
                }
            }
        }
        checked = true
    }
    if (!checked || locked != null || inputError.isNotBlank()) {
        Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            when {
                !checked -> Text("正在检查文件…")
                locked != null -> {
                    Text("先解锁待处理PDF", style = MaterialTheme.typography.titleLarge)
                    Text(request.names.getOrNull(request.files.indexOf(locked)) ?: locked!!.name)
                    PdfReader(locked!!, Modifier.fillMaxWidth().weight(1f), onWorkingCopyReady = {
                        vm.replaceToolInput(locked!!, it)
                    })
                }
                else -> Text("文件读取失败：$inputError", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    key(request.tool.id, inputKey) {
        when (request.tool.id) {
            "images_to_pdf" -> ImagesToPdfFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_merge" -> PdfMergeFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_split" -> PdfSplitFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_compress" -> PdfCompressFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_to_images" -> PdfToImagesFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_text" -> PdfTextFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_encrypt" -> PdfEncryptFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_office" -> PdfOfficeFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_sign" -> PdfSignFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_compare" -> PdfCompareFlow(request, vm, onBack, onOpenDoc, modifier)
            "ocr" -> OcrFlow(request, vm, onBack, onOpenDoc, modifier)
            "batch_extract", "table_xlsx" -> StructuredWorkbench(request, vm, onBack, modifier)
            "card" -> CardFlow(request, vm, onBack, onOpenDoc, modifier)
            "barcode" -> BarcodeFlow(request, vm, onBack, onOpenDoc, modifier)
            "long_image" -> LongImageFlow(request, vm, onBack, onOpenDoc, modifier)
            "image_edit" -> ImageEditFlow(request, vm, onBack, onOpenDoc, modifier)
            else -> UnknownFlow(request, vm, onBack, onOpenDoc, modifier)
        }
    }
}

// ======================================================================
// 通用流程骨架：配置 → 处理中 → 结果
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolFlow(
    request: ToolRequest,
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    runLabel: String = "开始处理",
    showInputPreview: Boolean = true,
    resultExtra: @Composable (FlowOutcome) -> Unit = {},
    config: @Composable () -> Unit,
    process: suspend (Context) -> FlowOutcome
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by rememberToolState(request, "stage") { 0 }
    var outcome by rememberToolState<FlowOutcome?>(request, "outcome") { null }
    var busy by remember { mutableStateOf(false) }
    var pendingSaveFiles by rememberToolState<List<File>>(request, "pendingSaveFiles") { emptyList() }
    var saveStatus by rememberToolState(request, "saveStatus") { "" }
    var showInputAfterProcessing by rememberToolState(request, "showInputAfterProcessing") { false }

    LaunchedEffect(request) {
        if (stage == 1 && !vm.isToolRunning) {
            stage = 0
            saveStatus = "上次处理已中断，配置和编辑清单已恢复，请确认后重新处理。"
        }
    }

    val saveSingle = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSaveFiles.singleOrNull()
        if (uri != null && file != null) scope.launch {
            busy = true
            val savedLabel = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().buffered().use { input -> input.copyTo(output) }
                    } ?: error("无法创建目标文件")
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    val label = OutputHistoryStore.describeDestination(context, uri)
                    OutputHistoryStore.markSaved(context, file, uri, label)
                    label
                }.getOrNull()
            }
            saveStatus = savedLabel?.let { "已保存：$it" } ?: "保存失败，请重新选择位置"
            busy = false
        }
    }
    val saveMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val files = pendingSaveFiles
        if (treeUri != null && files.isNotEmpty()) scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问目标文件夹")
                    var count = 0
                    files.forEach { file ->
                        val child = folder.createFile(guessMime(file), file.name) ?: return@forEach
                        context.contentResolver.openOutputStream(child.uri, "w")?.use { output ->
                            file.inputStream().buffered().use { input -> input.copyTo(output) }
                        } ?: return@forEach
                        val label = "${folder.name ?: "所选文件夹"} · ${child.name ?: file.name}"
                        OutputHistoryStore.markSaved(context, file, child.uri, label)
                        count++
                    }
                    count
                }.getOrDefault(0)
            }
            saveStatus = if (saved == files.size) "已保存 $saved 个文件到所选文件夹" else "已保存 $saved/${files.size} 个文件"
            busy = false
        }
    }

    fun saveToPhone(files: List<File>) {
        if (files.isEmpty() || busy) return
        pendingSaveFiles = files
        if (files.size == 1) saveSingle.launch(files.first().name) else saveMany.launch(null)
    }

    fun start() {
        if (busy || vm.isToolRunning) return
        busy = true
        stage = 1
        vm.runTool {
            val result = withContext(Dispatchers.IO) {
                runCatching { process(context) }
                    .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; FlowOutcome(listOf("出错" to (it.message ?: "处理失败")), emptyList()) }
            }
            result.files.forEach { OutputHistoryStore.recordGenerated(context, it, guessMime(it)) }
            outcome = result
            saveStatus = if (result.files.isNotEmpty()) "应用内部结果，尚未保存到手机" else ""
            showInputAfterProcessing = false
            busy = false
            stage = 2
        }
    }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(request.tool.label) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (stage) {
                    0 -> {
                        Button(onClick = { start() }, modifier = Modifier.weight(1f)) { Text(runLabel) }
                        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
                    }
                    1 -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        val o = outcome
                        Button(onClick = { saveToPhone(o?.files.orEmpty()) }, enabled = !busy && !o?.files.isNullOrEmpty(), modifier = Modifier.weight(1f)) {
                            Text(if (busy) "保存中…" else "保存到手机")
                        }
                        TextButton(
                            onClick = {
                                if (o != null && o.files.isNotEmpty()) {
                                    Share.files(context, o.files, guessMime(o.files.first()))
                                } else {
                                    toast("没有可分享的文件")
                                }
                            },
                            enabled = !busy && !o?.files.isNullOrEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("分享") }
                        TextButton(
                            onClick = {
                                val file = o?.files?.singleOrNull()
                                if (file == null || !Share.open(context, file, guessMime(file))) toast("当前结果无法打开")
                            },
                            enabled = o?.files?.size == 1,
                            modifier = Modifier.weight(1f)
                        ) { Text("打开") }
                    }
                }
            }
        }
    ) { padding ->
        when (stage) {
            0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("已选 ${request.files.size} 个文件", style = MaterialTheme.typography.titleSmall)
                request.names.forEach { name ->
                    Text(name, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showInputPreview) FilePreview(request.files, "输入预览")
                if (saveStatus.isNotBlank()) Text(saveStatus)
                config()
            }

            1 -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("处理中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val o = outcome
                Text(if (o == null || o.summary.any { it.first == "出错" }) "处理失败" else if (o.files.isEmpty()) "未生成文件" else "处理完成", style = MaterialTheme.typography.titleMedium)
                if (o != null) {
                    if (o.files.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showInputAfterProcessing, { showInputAfterProcessing = false }, label = { Text("处理结果") })
                            FilterChip(showInputAfterProcessing, { showInputAfterProcessing = true }, label = { Text("原文件") })
                        }
                        FilePreview(if (showInputAfterProcessing) request.files else o.files, if (showInputAfterProcessing) "原文件预览" else "真实结果预览")
                    }
                    o.summary.forEach { (k, v) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("$k：", style = MaterialTheme.typography.bodyMedium)
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (o.files.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("产出文件", style = MaterialTheme.typography.titleSmall)
                        o.files.forEach { f ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${f.name} · ${fmtSize(f.length())}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { if (!Share.open(context, f, guessMime(f))) toast("无法打开 ${f.name}") }) { Text("打开") }
                            }
                        }
                        Text(
                            saveStatus.ifBlank { "应用内部结果，尚未保存到手机" },
                            color = if (saveStatus.startsWith("已保存")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (o.docFiles.isNotEmpty()) {
                        Button(onClick = {
                            scope.launch {
                                val id = vm.saveFilesAsDoc("${request.tool.label}_${stamp()}", o.docFiles)
                                if (id != null) onOpenDoc(id) else toast("保存失败")
                            }
                        }) { Text("存入文档库") }
                    }
                    resultExtra(o)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { stage = 0 }) { Text("重新处理") }
                    TextButton(onClick = onBack) { Text("完成") }
                }
            }
        }
    }
}

private fun guessMime(file: File): String = when (file.extension.lowercase(Locale.getDefault())) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> "*/*"
}

@Composable
private fun PdfCompareFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, config = {
        Text("选择两份PDF；按页码对齐，左侧原稿、右侧对照稿，差异标红。渲染差异也可能来自字体或排版变化。")
    }) { context ->
        require(request.files.size == 2) { "请选择恰好两份PDF" }
        val output = File(FileStore.exportDir(context), "PDF差异_${System.currentTimeMillis()}.pdf")
        val (changed, notes) = com.localdoc.scanner.pdf.PdfComparison.compare(context, request.files[0], request.files[1], output)
        val report = File(FileStore.exportDir(context), "PDF对比_${System.currentTimeMillis()}.txt")
        report.writeText(notes.joinToString("\n"))
        FlowOutcome(listOf("差异页" to "$changed"), listOfNotNull(report, output.takeIf { it.isFile }))
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}

// ======================================================================
// 1. 图片转 PDF
// ======================================================================

@Composable
private fun ImagesToPdfFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var pageSize by rememberToolState(request, "pageSize") { 0 }
    var quality by rememberToolState(request, "quality") { 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("页面尺寸", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("A4", "原尺寸"), pageSize) { pageSize = it }
            Text("清晰度", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("高", "中", "低"), quality) { quality = it }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "生成 PDF", modifier = modifier, config = { panel() }
    ) { ctx ->
        val maxSide = intArrayOf(2400, 1600, 1100)[quality]
        val out = File(FileStore.exportDir(ctx), "images_${stamp()}.pdf")
        val size = if (pageSize == 0) PdfExporter.PageSize.A4 else PdfExporter.PageSize.FIT_IMAGE
        val ok = out.outputStream().use { PdfExporter.exportFiles(request.files, it, size, maxSide) }
        val bytes = if (ok) out.length() else 0L
        if (!ok) out.delete()
        FlowOutcome(
            listOf("页数" to "${request.files.size}", "体积" to fmtSize(bytes)),
            if (ok) listOf(out) else emptyList()
        )
    }
}

// ======================================================================
// 2. PDF 合并
// ======================================================================

@Composable
private fun PdfMergeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var order by rememberToolState(request, "order") { request.files.toList() }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("合并顺序（可调整）", style = MaterialTheme.typography.titleSmall)
            order.forEachIndexed { index, file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${index + 1}. ${file.name}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (index > 0) {
                            val m = order.toMutableList()
                            val t = m[index - 1]
                            m[index - 1] = m[index]
                            m[index] = t
                            order = m
                        }
                    }) { Text("↑") }
                    TextButton(onClick = {
                        if (index < order.lastIndex) {
                            val m = order.toMutableList()
                            val t = m[index + 1]
                            m[index + 1] = m[index]
                            m[index] = t
                            order = m
                        }
                    }) { Text("↓") }
                }
            }
            Text("合并会保留原页面尺寸和可选文字层。", style = MaterialTheme.typography.bodySmall)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "合并", modifier = modifier, config = { panel() }
    ) { ctx ->
        val out = File(FileStore.exportDir(ctx), "merge_${stamp()}.pdf")
        val ok = PdfTools.merge(order, out)
        FlowOutcome(
            listOf("输入" to "${order.size} 份", "体积" to fmtSize(if (ok) out.length() else 0)),
            if (ok) listOf(out) else emptyList()
        )
    }
}

// ======================================================================
// 3. PDF 拆分
// ======================================================================

@Composable
private fun PdfSplitFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var mode by rememberToolState(request, "mode") { 0 }
    var perFile by rememberToolState(request, "perFile") { "1" }
    var ranges by rememberToolState(request, "ranges") { "1-1" }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("拆分方式", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("每份 N 页", "自定义范围"), mode) { mode = it }
            if (mode == 0) {
                OutlinedTextField(
                    value = perFile, onValueChange = { perFile = it.filter { c -> c.isDigit() } },
                    label = { Text("每份页数") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = ranges, onValueChange = { ranges = it },
                    label = { Text("页范围，如 1-3,5,8-10") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Text("拆分会保留原页面内容和可选文字层。", style = MaterialTheme.typography.bodySmall)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "拆分", modifier = modifier, config = { panel() }
    ) { ctx ->
        val total = PdfTools.pageCount(src)
        val list = if (mode == 0) {
            val n = perFile.toIntOrNull()?.coerceAtLeast(1) ?: 1
            (1..total).chunked(n).map { it.first()..it.last() }
        } else {
            ranges.split(",").mapNotNull { part ->
                val p = part.trim()
                when {
                    p.contains("-") -> {
                        val (a, b) = p.split("-").map { it.trim().toIntOrNull() }
                        if (a != null && b != null) a.coerceAtLeast(1)..b.coerceAtMost(total) else null
                    }
                    else -> p.toIntOrNull()?.let { it..it }
                }
            }
        }
        val outDir = File(FileStore.exportDir(ctx), "split_${stamp()}").apply { mkdirs() }
        val files = PdfTools.split(src, list, outDir)
        FlowOutcome(
            listOf("原文档页数" to "$total", "拆出份数" to "${files.size}"),
            files
        )
    }
}

// ======================================================================
// 4. PDF 压缩
// ======================================================================

@Composable
private fun PdfCompressFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var tier by rememberToolState(request, "tier") { 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("压缩档位", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("微信", "邮件", "打印", "高清归档"), tier) { tier = it }
            Text("仅压缩图片，保留可搜索文字、表单和批注。图片清晰度会降低；已经较小的文件保持原样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "压缩", modifier = modifier, config = { panel() }
    ) { ctx ->
        val dpi = intArrayOf(110, 140, 200, 260)[tier]
        val quality = intArrayOf(58, 70, 86, 94)[tier]
        val before = src.length()
        val out = File(FileStore.exportDir(ctx), "compress_${stamp()}.pdf")
        val result = PdfTools.compressPreservingContent(src, out, dpi, quality)
        val after = out.length()
        val saved = if (before > 0) ((before - after) * 100 / before) else 0
        FlowOutcome(
            listOf(
                "压缩前" to fmtSize(before),
                "压缩后" to fmtSize(after),
                "节省" to "$saved%",
                "图片处理" to if (result.retainedOriginal) "没有可进一步缩小的图片，已保留原文件内容" else "已优化${result.optimizedImages}张图片，文字与表单保留"
            ),
            listOf(out)
        )
    }
}

// ======================================================================
// 5. PDF 转图片
// ======================================================================

@Composable
private fun PdfToImagesFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var dpi by rememberToolState(request, "dpi") { 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导出清晰度", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("高(200dpi)", "中(150dpi)", "低(110dpi)"), dpi) { dpi = it }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "导出图片", modifier = modifier, config = { panel() }
    ) { ctx ->
        val outDir = File(FileStore.exportDir(ctx), "img_${stamp()}").apply { mkdirs() }
        val files = PdfTools.toImages(src, outDir, intArrayOf(200, 150, 110)[dpi])
        FlowOutcome(listOf("导出页数" to "${files.size}"), files, files)
    }
}

@Composable
private fun PdfTextFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val clipboard = LocalClipboardManager.current
    ToolFlow(
        request = request,
        vm = vm,
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        runLabel = "提取文字",
        modifier = modifier,
        resultExtra = { outcome ->
            val text = outcome.files.firstOrNull()?.takeIf { it.exists() }?.readText().orEmpty()
            if (text.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制全部") }
            }
        },
        config = {
            Text("原生文字PDF会直接保留文字顺序；扫描图片PDF没有文字层时，请改用“文字识别”。")
        }
    ) { ctx ->
        val text = PdfTools.extractText(request.files.first())
        if (text.isBlank()) {
            FlowOutcome(listOf("结果" to "没有提取到文字，可能是扫描图片PDF"), emptyList())
        } else {
            val out = File(FileStore.exportDir(ctx), "pdf_text_${stamp()}.txt")
            out.writeText(text, Charsets.UTF_8)
            FlowOutcome(listOf("字数" to "${text.length}", "预览" to text.take(120)), listOf(out))
        }
    }
}

@Composable
private fun PdfEncryptFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ToolFlow(
        request = request,
        vm = vm,
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        runLabel = "生成加密副本",
        modifier = modifier,
        config = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("打开密码") }, singleLine = true)
                OutlinedTextField(confirm, { confirm = it }, label = { Text("再次输入密码") }, singleLine = true)
                Text("原PDF不会被覆盖；忘记密码后本App无法恢复。", style = MaterialTheme.typography.bodySmall)
            }
        }
    ) { ctx ->
        when {
            password.isBlank() -> FlowOutcome(listOf("出错" to "密码不能为空"), emptyList())
            password != confirm -> FlowOutcome(listOf("出错" to "两次密码不一致"), emptyList())
            else -> {
                val source = request.files.first()
                val out = File(FileStore.exportDir(ctx), "${source.nameWithoutExtension}_加密_${stamp()}.pdf")
                val ok = PdfTools.encrypt(source, out, password)
                FlowOutcome(
                    listOf("结果" to if (ok) "已生成256位加密副本" else "加密失败"),
                    if (ok) listOf(out) else emptyList()
                )
            }
        }
    }
}

// ======================================================================
// 6. PDF 编辑与填写
// ======================================================================

@Composable
private fun PdfSignFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var storeUri by rememberToolState<Uri?>(request, "signingStoreUri") { null }
    var password by remember(request) { mutableStateOf("") }
    var certificates by remember { mutableStateOf<List<com.localdoc.scanner.pdf.SigningCertificate>>(emptyList()) }
    var selectedAlias by rememberToolState(request, "signingAlias") { "" }
    var reason by rememberToolState(request, "signingReason") { "" }
    var name by rememberToolState(request, "signingName") { "" }
    var certificateStatus by remember { mutableStateOf("") }
    var inspecting by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        storeUri = uri; certificates = emptyList(); selectedAlias = ""; password = ""
    }
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, runLabel = "生成数字签名PDF", config = {
        Text("使用包含私钥的PKCS12证书（.p12/.pfx）生成可验证的数字签名。请先完成所有内容编辑；再次修改会影响签名有效性。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (storeUri == null) "选择证书文件" else "重新选择证书") }
        OutlinedTextField(password, { password = it }, label = { Text("证书密码，不会保存") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val uri = storeUri ?: return@Button
            val secret = password.toCharArray()
            inspecting = true
            scope.launch {
                try {
                    certificates = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { com.localdoc.scanner.pdf.PdfDigitalSignature.inspect(it, secret) } ?: error("无法读取证书文件")
                    }
                    selectedAlias = certificates.firstOrNull()?.alias.orEmpty()
                    certificateStatus = "找到${certificates.size}个私钥证书"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    certificateStatus = "证书读取失败：${e.message}"
                } finally { secret.fill('\u0000'); inspecting = false }
            }
        }, enabled = storeUri != null && !inspecting) { Text(if (inspecting) "读取中…" else "查看并选择证书") }
        if (certificateStatus.isNotBlank()) Text(certificateStatus)
        certificates.forEach { cert ->
            FilterChip(selectedAlias == cert.alias, { selectedAlias = cert.alias }, label = { Text("${cert.alias} · ${cert.subject}") })
            if (selectedAlias == cert.alias) Text("签发者：${cert.issuer}\n有效期至：${cert.notAfter}\nSHA256：${cert.sha256}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(name, { name = it }, label = { Text("签名显示名称（可留空）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reason, { reason = it }, label = { Text("签名说明（可留空）") }, modifier = Modifier.fillMaxWidth())
        Text("签名可校验文件完整性；身份可信度取决于证书签发者与阅读器的信任设置。当前不连接时间戳服务。", style = MaterialTheme.typography.bodySmall)
    }) { ctx ->
        val uri = storeUri ?: error("请先选择含私钥的证书文件")
        val secret = password.toCharArray()
        val output = File(FileStore.exportDir(ctx), "${request.files.first().nameWithoutExtension}_数字签名_${stamp()}.pdf")
        try {
            val info = ctx.contentResolver.openInputStream(uri)?.use {
                com.localdoc.scanner.pdf.PdfDigitalSignature.sign(request.files.first(), output, it, secret, selectedAlias, reason, name)
            } ?: error("无法读取证书文件")
            FlowOutcome(listOf("结果" to "已生成数字签名副本", "证书" to info.subject, "证书指纹SHA256" to info.sha256), listOf(output))
        } finally { secret.fill('\u0000'); password = "" }
    }
}

@Composable
private fun PdfOfficeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val source = request.files.first()
    val operations = listOf("页码水印", "填写文字", "文字标记", "便签", "手写签名", "图片签名", "表单填写", "永久打码", "管理批注", "修改原文字")
    var sensitiveRegions by rememberToolState<List<com.localdoc.scanner.pdf.SensitiveRegion>>(request, "sensitiveRegions") { emptyList() }
    var sensitiveKeywords by rememberToolState(request, "sensitiveKeywords") { "" }
    var sensitiveStatus by rememberToolState(request, "sensitiveStatus") { "" }
    var detectingSensitive by rememberToolState(request, "detectingSensitive") { false }
    LaunchedEffect(request) { if (!vm.isToolRunning) detectingSensitive = false }
    var operation by rememberToolState(request, "operation") { 0 }
    val pageCount = remember(source) { PdfTools.pageCount(source).coerceAtLeast(1) }
    var pageIndex by rememberToolState(request, "pageIndex") { 0 }
    var text by rememberToolState(request, "text") { "" }
    var header by rememberToolState(request, "header") { "" }
    var footer by rememberToolState(request, "footer") { "" }
    var watermark by rememberToolState(request, "watermark") { "" }
    var addPageNumbers by rememberToolState(request, "addPageNumbers") { true }
    var numberStart by rememberToolState(request, "numberStart") { "1" }
    var numberPrefix by rememberToolState(request, "numberPrefix") { "" }
    var numberSuffix by rememberToolState(request, "numberSuffix") { " / {total}" }
    var numberPosition by rememberToolState(request, "numberPosition") { 5 }
    var watermarkAngle by rememberToolState(request, "watermarkAngle") { 32f }
    var opacity by rememberToolState(request, "opacity") { 0.22f }
    var x by rememberToolState(request, "x") { 0.12f }
    var y by rememberToolState(request, "y") { 0.20f }
    var width by rememberToolState(request, "width") { 0.42f }
    var height by rememberToolState(request, "height") { 0.08f }
    var undoPlacements by rememberToolState<List<PdfPlacement>>(request, "undoPlacements") { emptyList() }
    var redoPlacements by rememberToolState<List<PdfPlacement>>(request, "redoPlacements") { emptyList() }
    var markup by rememberToolState(request, "markup") { 0 }
    var strokes by rememberToolState<List<List<PdfInkPoint>>>(request, "strokes") { emptyList() }
    var signatureUri by rememberToolState<Uri?>(request, "signatureUri") { null }
    var watermarkUri by rememberToolState<Uri?>(request, "watermarkUri") { null }
    var pendingEdits by rememberToolState<List<PendingPdfEdit>>(request, "pendingEdits") { emptyList() }
    var annotations by remember(source) { mutableStateOf<List<com.localdoc.scanner.pdf.PdfAnnotationEntry>>(emptyList()) }
    var annotationError by remember { mutableStateOf("") }
    var annotationKey by rememberToolState(request, "annotationKey") { "" }
    var annotationText by rememberToolState(request, "annotationText") { "" }
    var annotationDelete by rememberToolState(request, "annotationDelete") { false }
    var textObjects by remember(source, pageIndex) { mutableStateOf<List<com.localdoc.scanner.pdf.PdfTextObject>>(emptyList()) }
    var textObjectError by remember(source, pageIndex) { mutableStateOf("") }
    var textObjectKey by rememberToolState(request, "textObjectKey") { "" }
    var replacementText by rememberToolState(request, "replacementText") { "" }
    LaunchedEffect(source, pageIndex, operation) {
        if (operation == 9) runCatching { withContext(Dispatchers.IO) { com.localdoc.scanner.pdf.PdfOriginalTextEditor.list(source, pageIndex) } }
            .onSuccess { textObjects = it }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                textObjectError = "读取文字对象失败：${it.message}"
            }
    }
    LaunchedEffect(source) {
        runCatching { withContext(Dispatchers.IO) { com.localdoc.scanner.pdf.PdfAnnotationEditor.list(source) } }
            .onSuccess { annotations = it }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                annotationError = "读取批注失败：${it.message}"
            }
    }
    val formFields = remember(source) { PdfOfficeTools.formFields(source) }
    var formValues by rememberToolState<Map<String, String>>(request, "formValues") { emptyMap() }
    LaunchedEffect(formFields) {
        formFields.filter { !it.readOnly && it.type != "PDSignatureField" }.forEach { field -> if (field.name !in formValues) formValues = formValues + (field.name to field.value) }
    }

    var preview by remember(source) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, pageIndex) {
        val next = withContext(Dispatchers.IO) { PdfTools.renderPage(source, pageIndex, 1200) }
        preview?.takeIf { it !== next }?.recycle()
        preview = next
    }
    DisposableEffect(source) { onDispose { preview?.recycle() } }
    var signaturePreview by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkPreview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(signatureUri) {
        val next = signatureUri?.let { withContext(Dispatchers.IO) { ImageIo.loadFromUri(context, it, 1200) } }
        signaturePreview?.takeIf { it !== next }?.recycle()
        signaturePreview = next
    }
    LaunchedEffect(watermarkUri) {
        val next = watermarkUri?.let { withContext(Dispatchers.IO) { ImageIo.loadFromUri(context, it, 1200) } }
        watermarkPreview?.takeIf { it !== next }?.recycle()
        watermarkPreview = next
    }
    DisposableEffect(Unit) {
        onDispose { signaturePreview?.recycle(); watermarkPreview?.recycle() }
    }

    val signaturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        signatureUri = uri
    }
    val watermarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        watermarkUri = uri
    }

    fun snapshotCurrent() = PendingPdfEdit(
        operation = operation,
        label = if (operation in setOf(1, 2, 3, 4, 5, 7, 8)) "第${pageIndex + 1}页 · ${operations[operation]}${if (operation == 8 && annotationDelete) "：移除" else ""}" else operations[operation],
        pageIndex = pageIndex,
        text = text,
        header = header,
        footer = footer,
        watermark = watermark,
        addPageNumbers = addPageNumbers,
        opacity = opacity,
        x = x,
        y = y,
        width = width,
        height = height,
        markup = PdfMarkup.entries[markup],
        strokes = strokes.map { it.toList() },
        signatureUri = signatureUri,
        watermarkUri = watermarkUri,
        formValues = formValues.toMap(),
        annotationChange = if (operation == 8) com.localdoc.scanner.pdf.PdfAnnotationChange(annotationKey, pageIndex, annotationDelete, annotationText) else null,
        decoration = com.localdoc.scanner.pdf.PdfDecorationOptions(numberStart.toIntOrNull() ?: 0, numberPrefix, numberSuffix, numberPosition, watermarkAngle),
        textChange = if (operation == 9) com.localdoc.scanner.pdf.PdfTextChange(textObjectKey, pageIndex, replacementText) else null
    )

    fun currentCanApply(): Boolean = when (operation) {
        0 -> (numberStart.toIntOrNull() ?: 0) in 1..999999 && numberPrefix.length <= 40 && numberSuffix.length <= 40
        1 -> text.isNotBlank() && text.length <= 300 && !text.contains('\n')
        3 -> text.isNotBlank() && text.length <= 1000
        4 -> strokes.any { it.size >= 2 }
        5 -> signatureUri != null
        6 -> formFields.isNotEmpty()
        8 -> annotations.any { it.key == annotationKey && it.pageIndex == pageIndex && it.editable } && (annotationDelete || annotationText.length <= 1000)
        9 -> textObjects.any { it.key == textObjectKey && it.editable } && replacementText.length <= 300 && '\n' !in replacementText && '\r' !in replacementText
        else -> true
    }

    @Composable
    fun positionControls(showSize: Boolean = true) {
        Text("位置：左 ${"%.0f".format(x * 100)}% · 上 ${"%.0f".format(y * 100)}%（直接在页面拖动）", style = MaterialTheme.typography.bodySmall)
        if (showSize) {
            Text("范围：宽 ${"%.0f".format(width * 100)}% · 高 ${"%.0f".format(height * 100)}%", style = MaterialTheme.typography.bodySmall)
            Slider(width, { width = it }, valueRange = 0.08f..0.85f)
            Slider(height, { height = it }, valueRange = 0.025f..0.35f)
        }
    }

    @Composable
    fun panel() {
        Text("编辑类型", style = MaterialTheme.typography.titleSmall)
        ChipRow(operations, operation) { operation = it }
        if (operation != 0 && pageCount > 1) {
            Text("第 ${pageIndex + 1} / $pageCount 页")
            Slider(
                value = pageIndex.toFloat(),
                onValueChange = { pageIndex = it.toInt().coerceIn(0, pageCount - 1) },
                valueRange = 0f..(pageCount - 1).toFloat(),
                steps = (pageCount - 2).coerceAtLeast(0)
            )
        }
        PdfEditPreview(
            source = source,
            bitmap = preview,
            pageIndex = pageIndex,
            pageCount = pageCount,
            operation = operation,
            text = text,
            header = header,
            footer = footer,
            watermark = watermark,
            addPageNumbers = addPageNumbers,
            opacity = opacity,
            x = x,
            y = y,
            width = width,
            height = height,
            markup = PdfMarkup.entries[markup],
            strokes = strokes,
            signatureBitmap = signaturePreview,
            watermarkBitmap = watermarkPreview,
            decoration = snapshotCurrent().decoration!!,
            onRectChange = { nx, ny, nw, nh -> x = nx; y = ny; width = nw; height = nh },
            onInteractionStart = {
                val current = PdfPlacement(x, y, width, height)
                if (undoPlacements.lastOrNull() != current) undoPlacements = (undoPlacements + current).takeLast(30)
                redoPlacements = emptyList()
            },
            onPreviousPage = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
            onNextPage = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
            onSelectPage = { pageIndex = it.coerceIn(0, pageCount - 1) }
        )
        if (operation in 1..5 || operation == 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val previous = undoPlacements.lastOrNull() ?: return@TextButton
                    redoPlacements = (redoPlacements + PdfPlacement(x, y, width, height)).takeLast(30)
                    undoPlacements = undoPlacements.dropLast(1)
                    x = previous.x; y = previous.y; width = previous.width; height = previous.height
                }, enabled = undoPlacements.isNotEmpty()) { Text("撤销位置") }
                TextButton(onClick = {
                    val next = redoPlacements.lastOrNull() ?: return@TextButton
                    undoPlacements = (undoPlacements + PdfPlacement(x, y, width, height)).takeLast(30)
                    redoPlacements = redoPlacements.dropLast(1)
                    x = next.x; y = next.y; width = next.width; height = next.height
                }, enabled = redoPlacements.isNotEmpty()) { Text("重做位置") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val edit = snapshotCurrent()
                    pendingEdits = pendingEdits.filter {
                        (edit.annotationChange == null || it.annotationChange?.key != edit.annotationChange.key) &&
                            (edit.textChange == null || it.textChange?.key != edit.textChange.key)
                    } + edit
                },
                enabled = currentCanApply()
            ) { Text("加入本次编辑") }
            if (pendingEdits.isNotEmpty()) TextButton(onClick = { pendingEdits = emptyList() }) { Text("清空清单") }
        }
        if (pendingEdits.isNotEmpty()) {
            Text("本次将一次生成 ${pendingEdits.size} 项编辑：", style = MaterialTheme.typography.titleSmall)
            pendingEdits.forEachIndexed { index, edit ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${edit.label}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { pendingEdits = pendingEdits.toMutableList().also { it.removeAt(index) } }) { Text("移除") }
                }
            }
            Text("清单非空时只生成清单里的项目；当前设置请先加入。生成后会重新渲染全部编辑供核对。", style = MaterialTheme.typography.bodySmall)
        }
        when (operation) {
            0 -> {
                OutlinedTextField(watermark, { watermark = it }, label = { Text("文字水印（可留空）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { watermarkPicker.launch(arrayOf("image/*")) }) {
                    Text(if (watermarkUri == null) "选择图片水印（可选）" else "重新选择图片水印")
                }
                OutlinedTextField(header, { header = it }, label = { Text("页眉（可留空）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(footer, { footer = it }, label = { Text("页脚（可留空）") }, modifier = Modifier.fillMaxWidth())
                FilterChip(addPageNumbers, { addPageNumbers = !addPageNumbers }, label = { Text("添加页码") })
                if (addPageNumbers) {
                    OutlinedTextField(numberStart, { numberStart = it.filter(Char::isDigit).take(6) }, label = { Text("起始页码 1—999999") }, singleLine = true)
                    OutlinedTextField(numberPrefix, { numberPrefix = it }, label = { Text("页码前缀，最多40字") }, singleLine = true)
                    OutlinedTextField(numberSuffix, { numberSuffix = it }, label = { Text("页码后缀；{total}表示总页数，最多40字") }, singleLine = true)
                    ChipRow(listOf("左上", "中上", "右上", "左下", "中下", "右下"), numberPosition) { numberPosition = it }
                }
                Text("水印角度 ${watermarkAngle.toInt()}°")
                Slider(watermarkAngle, { watermarkAngle = it }, valueRange = -90f..90f)
                Text("透明度 ${"%.0f".format(opacity * 100)}%")
                Slider(opacity, { opacity = it }, valueRange = 0.08f..0.7f)
            }
            1 -> {
                OutlinedTextField(text, { text = it }, label = { Text("填写文字（单行，上限300字）") }, modifier = Modifier.fillMaxWidth())
                Text("${text.length}/300", color = if (text.length > 300 || text.contains('\n')) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                positionControls(showSize = false)
            }
            2 -> {
                ChipRow(listOf("高亮", "下划线", "删除线"), markup) { markup = it }
                positionControls()
            }
            3 -> {
                OutlinedTextField(text, { text = it }, label = { Text("便签内容") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                positionControls(showSize = false)
            }
            4 -> {
                Text("在下方签名；可清除重写。", style = MaterialTheme.typography.bodySmall)
                SignaturePad(strokes = strokes, onStrokesChange = { strokes = it })
                TextButton(onClick = { strokes = emptyList() }) { Text("清除签名") }
                positionControls()
            }
            5 -> {
                Button(onClick = { signaturePicker.launch(arrayOf("image/*")) }) { Text(if (signatureUri == null) "选择签名图片" else "重新选择签名图片") }
                Text(signatureUri?.lastPathSegment ?: "尚未选择", style = MaterialTheme.typography.bodySmall)
                positionControls(showSize = true)
            }
            6 -> {
                if (formFields.isEmpty()) Text("这份PDF没有检测到AcroForm表单字段。")
                PdfFormEditor(formFields, formValues) { name, value -> formValues = formValues + (name to value) }
            }
            7 -> {
                Text("黑框区域会被永久写入栅格页面，原文字和对象不会留在输出PDF中。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(sensitiveKeywords, { sensitiveKeywords = it }, label = { Text("额外敏感关键词，逗号分隔（可留空）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val page = pageIndex
                    val keywords = sensitiveKeywords.split(',', '，').map(String::trim).filter(String::isNotEmpty)
                    detectingSensitive = true
                    sensitiveStatus = "正在用medium识别第${page + 1}页…"
                    vm.runTool {
                        try {
                            val found = withContext(Dispatchers.IO) { com.localdoc.scanner.pdf.SensitiveRegionDetector.detect(context, source, page, keywords) }
                            sensitiveRegions = sensitiveRegions.filter { it.page != page } + found
                            sensitiveStatus = "第${page + 1}页发现${found.size}个候选。请确认；未命中不代表没有敏感信息。"
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            sensitiveStatus = "识别失败：${e.message}"
                        } finally { detectingSensitive = false }
                    }
                }, enabled = !detectingSensitive && !vm.isToolRunning) { Text(if (detectingSensitive) "识别中…" else "查找本页敏感信息") }
                if (sensitiveStatus.isNotBlank()) Text(sensitiveStatus, style = MaterialTheme.typography.bodySmall)
                Text("候选范围覆盖整条识别行；使用后可拖动框调整。不会自动打码。", style = MaterialTheme.typography.bodySmall)
                sensitiveRegions.filter { it.page == pageIndex }.forEach { region ->
                    TextButton(onClick = { x = region.rect.x; y = region.rect.y; width = region.rect.width; height = region.rect.height }) {
                        Text("使用范围：${region.label} · ${region.text.take(55)}")
                    }
                }
                positionControls()
            }
            8 -> {
                if (annotationError.isNotBlank()) Text(annotationError, color = MaterialTheme.colorScheme.error)
                PdfAnnotationPanel(annotations.filter { it.pageIndex == pageIndex }, annotationKey, annotationText, annotationDelete,
                    onSelect = { annotationKey = it.key; annotationText = it.contents; annotationDelete = false },
                    onContents = { annotationText = it }, onDelete = { annotationDelete = it })
            }
            9 -> {
                Text("直接修改原页Tj/TJ文字对象，保留原字体和后续文字位置。新文字需要原字体支持，且不能超过原宽度。图片文字、竖排和嵌套Form暂不支持。", style = MaterialTheme.typography.bodySmall)
                if (textObjectError.isNotBlank()) Text(textObjectError, color = MaterialTheme.colorScheme.error)
                PdfTextObjectPanel(textObjects, textObjectKey, replacementText,
                    onSelect = { textObjectKey = it.key; replacementText = it.text }, onReplacement = { replacementText = it })
            }
        }
        Text("所有操作都会另存新PDF，原文件不会被覆盖。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    ToolFlow(
        request = request,
        vm = vm,
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        runLabel = if (pendingEdits.isEmpty()) "生成当前编辑副本" else "生成 ${pendingEdits.size} 项编辑副本",
        showInputPreview = false,
        modifier = modifier,
        config = { panel() }
    ) { context ->
        val edits = if (pendingEdits.isEmpty()) listOf(snapshotCurrent()) else pendingEdits
        val outLabel = if (edits.size > 1) "多项编辑" else operations[edits.first().operation]
        val out = File(FileStore.exportDir(context), "${source.nameWithoutExtension}_${outLabel}_${stamp()}.pdf")
        var currentInput = source
        var ok = edits.isNotEmpty()
        var complete = false
        try {
        edits.forEachIndexed { index, edit ->
            if (!ok) return@forEachIndexed
            val target = if (index == edits.lastIndex) out else File(context.cacheDir, "pdf-edit-${System.nanoTime()}-$index.pdf")
            ok = when (edit.operation) {
                0 -> {
                    val bitmap = edit.watermarkUri?.let { ImageIo.loadFromUri(context, it, 1600) }
                    try {
                        PdfOfficeTools.decorate(context, currentInput, target, edit.watermark, edit.header, edit.footer, edit.addPageNumbers, edit.opacity, bitmap, edit.decoration ?: com.localdoc.scanner.pdf.PdfDecorationOptions())
                    } finally { bitmap?.recycle() }
                }
                1 -> edit.text.isNotBlank() && PdfOfficeTools.addText(context, currentInput, target, edit.pageIndex, edit.text, edit.x, edit.y, 13f)
                2 -> PdfOfficeTools.addMarkup(currentInput, target, edit.pageIndex, edit.markup, edit.x, edit.y, edit.width, edit.height)
                3 -> edit.text.isNotBlank() && PdfOfficeTools.addNote(currentInput, target, edit.pageIndex, edit.text, edit.x, edit.y)
                4 -> edit.strokes.any { it.size >= 2 } && PdfOfficeTools.drawInk(currentInput, target, edit.pageIndex, edit.strokes, edit.x, edit.y, edit.width, edit.height)
                5 -> {
                    val bitmap = edit.signatureUri?.let { ImageIo.loadFromUri(context, it, 1600) }
                    if (bitmap == null) false else try {
                        PdfOfficeTools.addSignatureImage(currentInput, target, edit.pageIndex, bitmap, edit.x, edit.y, edit.width, edit.height)
                    } finally { bitmap.recycle() }
                }
                6 -> edit.formValues.isNotEmpty() && PdfOfficeTools.fillForm(context, currentInput, target, edit.formValues.filterKeys { name -> formFields.any { it.name == name && !it.readOnly && it.type != "PDSignatureField" } })
                8 -> edit.annotationChange?.let { com.localdoc.scanner.pdf.PdfAnnotationEditor.apply(currentInput, target, it) } ?: false
                9 -> edit.textChange?.let { com.localdoc.scanner.pdf.PdfOriginalTextEditor.apply(currentInput, target, it) } ?: false
                else -> PdfTools.redactPermanent(currentInput, target, edit.pageIndex, edit.x, edit.y, edit.width, edit.height)
            }
            if (currentInput !== source) currentInput.delete()
            if (ok) currentInput = target else target.delete()
        }
        complete = ok
        FlowOutcome(
            summary = listOf(
                "操作" to edits.joinToString("、") { it.label },
                "原文件" to fmtSize(source.length()),
                "新文件" to fmtSize(if (ok) out.length() else 0L),
                "结果" to if (ok) "已生成副本" else "处理失败，请检查内容和文件权限"
            ),
            files = if (ok) listOf(out) else emptyList()
        )
        } finally {
            if (currentInput !== source && currentInput !== out) currentInput.delete()
            if (!complete) out.delete()
        }
    }
}

@Composable
private fun SignaturePad(
    strokes: List<List<PdfInkPoint>>,
    onStrokesChange: (List<List<PdfInkPoint>>) -> Unit
) {
    val currentStrokes by rememberUpdatedState(strokes)
    Canvas(
        modifier = Modifier.fillMaxWidth().height(150.dp).background(Color.White)
            .pointerInput(Unit) {
                var base = emptyList<List<PdfInkPoint>>()
                val active = mutableListOf<PdfInkPoint>()
                detectDragGestures(
                    onDragStart = { position ->
                        base = currentStrokes
                        active.clear()
                        val point = PdfInkPoint(
                            (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                        active += point
                        onStrokesChange(base + listOf(active.toList()))
                    },
                    onDrag = { change, _ ->
                        val point = PdfInkPoint(
                            (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                        active += point
                        onStrokesChange(base + listOf(active.toList()))
                        change.consume()
                    }
                )
            }
    ) {
        strokes.forEach { stroke ->
            if (stroke.size >= 2) {
                val path = Path().apply {
                    moveTo(stroke.first().x * size.width, stroke.first().y * size.height)
                    stroke.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                }
                drawPath(path, Color.Black, style = Stroke(width = 3.5f))
            }
        }
    }
}

// ======================================================================
// 7. OCR 文字识别
// ======================================================================

@Composable
private fun OcrFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var precise by rememberToolState(request, "precise") { 1 }
    var searchable by rememberToolState(request, "searchable") { true }
    var manual by rememberToolState(request, "manual") { "" }
    val clipboard = LocalClipboardManager.current
    val engineAvailable = true

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别档位", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("普通(tiny)", "高精度(medium)"), precise) { precise = it }
            Row { Switch(searchable, { searchable = it }); Text("同时生成可搜索PDF") }
            if (engineAvailable) {
                Text("模型已内置，直接点下方按钮识别。", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "本地 OCR 暂不可用，可以手工粘贴或录入文字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = manual, onValueChange = { manual = it },
                    label = { Text("文字内容") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    maxLines = 8
                )
            }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "识别", modifier = modifier,
        resultExtra = { o ->
            val txt = o.files.firstOrNull()?.readText() ?: ""
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(txt)) }) { Text("复制") }
            }
        },
        config = { panel() }
    ) { ctx ->
        val engine = PaddleOcrEngine(ctx)
        val pages = mutableListOf<com.localdoc.scanner.export.SearchablePdfPage>()
        val failures = mutableListOf<String>()
        val raw = StringBuilder()
        val temporary = File(ctx.cacheDir, "ocr-output-${System.nanoTime()}").apply { mkdirs() }
        try {
            request.files.forEach { source ->
                val count = if (source.extension.equals("pdf", true)) PdfTools.pageCount(source) else 1
                if (count == 0) failures += "${source.name}：无法读取PDF，请先解锁"
                for (index in 0 until count) {
                    try {
                        val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, index, if (precise == 1) 3000 else 2000)
                            else ImageIo.loadFromFile(source, if (precise == 1) 3600 else 2400)
                        requireNotNull(bitmap) { "图片无法读取" }
                        try {
                            val recognized = engine.recognize(bitmap, precise == 1)
                            raw.append("【${source.name} 第${index + 1}页】\n${recognized.text}\n\n")
                            if (recognized.text.isBlank()) failures += "${source.name} 第${index + 1}页：没有识别到文字"
                            if (searchable) {
                                val image = File(temporary, "${pages.size}.jpg")
                                check(ImageIo.saveJpeg(bitmap, image, 94)) { "无法保留PDF页面" }
                                pages += com.localdoc.scanner.export.SearchablePdfPage(image, recognized.text, recognized.boxes)
                            }
                        } finally { bitmap.recycle() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failures += "${source.name} 第${index + 1}页：${e.message}"
                    }
                }
            }
            val outputs = mutableListOf<File>()
            val text = raw.toString()
            if (text.isNotBlank()) {
                val out = File(FileStore.exportDir(ctx), "ocr_${System.currentTimeMillis()}.txt")
                out.writeText(text); outputs += out
            }
            if (searchable && pages.isNotEmpty()) {
                val pdf = File(FileStore.exportDir(ctx), "ocr_可搜索_${System.currentTimeMillis()}.pdf")
                val ok = pdf.outputStream().use { com.localdoc.scanner.export.SearchablePdfExporter.export(ctx, pages, it) }
                if (ok) outputs += pdf else { pdf.delete(); failures += "可搜索PDF生成失败，文字结果保留" }
            }
            FlowOutcome(listOf("字数" to "${text.length}", "PDF页面" to "${pages.size}") +
                if (failures.isNotEmpty()) listOf("需复核" to failures.joinToString("\n")) else emptyList(), outputs)
        } finally {
            engine.close()
            temporary.listFiles()?.forEach { it.delete() }
            temporary.delete()
        }
    }
}

// ======================================================================
// 7. 条码识别
// ======================================================================

@Composable
private fun BarcodeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "识别条码", modifier = modifier,
        resultExtra = { o ->
            val txt = o.copyText ?: o.files.firstOrNull()?.readText()?.lineSequence()?.firstOrNull() ?: ""
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(txt)) }) { Text("复制") }
                if (Uri.parse(txt).scheme?.lowercase() in setOf("http", "https") && !Uri.parse(txt).host.isNullOrBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(txt))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("打开") }
                }
            }
        },
        config = {
            Text("将对选中的图片做一维码 / 二维码识别，纯本地解码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    ) { ctx ->
        val found = mutableListOf<String>()
        request.files.forEach { file ->
            val bmp = ImageIo.loadFromFile(file, 2000) ?: return@forEach
            BarcodeDecoder.decode(bmp).forEach { r ->
                found += r.text
            }
            bmp.recycle()
        }
        val out = File(FileStore.exportDir(ctx), "barcode_${stamp()}.txt")
        out.writeText(found.joinToString("\n"))
        FlowOutcome(
            if (found.isEmpty()) listOf("结果" to "没识别到条码")
            else found.mapIndexed { i, s -> "结果 ${i + 1}" to s },
            if (found.isEmpty()) emptyList() else listOf(out), copyText = found.firstOrNull()
        )
    }
}

// ======================================================================
// 8. 长图拼接
// ======================================================================

@Composable
private fun LongImageFlow(
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
    ) { ctx ->
        val maxW = intArrayOf(1080, 720, 480)[width]
        val stitched = Stitch.verticalFiles(request.files, maxW)
        val out = File(FileStore.exportDir(ctx), "long_${stamp()}.jpg")
        val dims = "${stitched.width}×${stitched.height}"
        val ok = try { ImageIo.saveJpeg(stitched, out, 88) } finally { stitched.recycle() }
        FlowOutcome(
            listOf("拼接页数" to "${request.files.size}", "尺寸" to dims, "体积" to fmtSize(if (ok) out.length() else 0)),
            if (ok) listOf(out) else emptyList(),
            if (ok) listOf(out) else emptyList()
        )
    }
}

// ======================================================================
// 9. 图片编辑
// ======================================================================

@Composable
private fun ImageEditFlow(
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
    ) { ctx ->
        // Capture configuration once; a running batch must not observe later UI changes.
        val inputs = order.toList(); val ratios = splits.toMap(); val operation = mode
        val chosenFilter = filter; val quarterTurns = rotation; val autoSkew = deskew
        val flatten = flattenBook; val reversed = rightFirst; val exportPdf = makePdf
        val outputs = mutableListOf<File>(); val notes = mutableListOf<String>(); val failures = mutableListOf<String>()
        val token = java.util.UUID.randomUUID().toString().take(8)
        val directory = FileStore.exportDir(ctx)
        var pageNumber = 0
        fun savePage(bitmap: Bitmap, suffix: String): File {
            val file = File(directory, "image_${token}_${(++pageNumber).toString().padStart(4, '0')}_$suffix.jpg")
            if (!ImageIo.saveJpeg(bitmap, file, 94)) { file.delete(); error("保存图片失败") }
            return file
        }
        if (operation == 2) {
            require(inputs.size == 2) { "证件拼版必须正好选择正反面两张图" }
            val owned = mutableListOf<Bitmap>()
            try {
                val pages = inputs.map { file ->
                    val source = (ImageIo.loadFromFile(file, 2400) ?: error("图片读取失败")).also { owned.add(it) }
                    com.localdoc.scanner.cv.ImageBatchProcessor.render(source, quarterTurns, autoSkew, chosenFilter).bitmap.also { owned.add(it) }
                }
                val sheet = DocumentLayouts.idCardSheet(pages[0], pages[1]).also { owned.add(it) }
                outputs.add(savePage(sheet, "证件双面"))
            } finally { owned.distinct().forEach { it.recycle() } }
        } else inputs.forEachIndexed { index, file ->
            val owned = mutableListOf<Bitmap>(); val currentOutputs = mutableListOf<File>()
            try {
                val source = (ImageIo.loadFromFile(file, 3200) ?: error("图片读取失败")).also { owned.add(it) }
                val pages = if (operation == 1) {
                    val ratio = ratios[file.absolutePath] ?: com.localdoc.scanner.cv.BookGutter.estimate(source).ratio
                    val pair = DocumentLayouts.splitBookSpread(source, splitRatio = ratio)
                    listOf(pair.first to "左页", pair.second to "右页").also { p -> owned.addAll(p.map { it.first }) }
                        .let { if (reversed) it.reversed() else it }
                } else listOf(source to "编辑")
                pages.forEach { (original, suffix) ->
                    val page = if (operation == 1 && flatten) BookDewarp.flatten(original).let {
                        owned.add(it.bitmap); notes.add("第${index + 1}张 $suffix：${it.note}"); it.bitmap
                    } else original
                    val result = com.localdoc.scanner.cv.ImageBatchProcessor.render(page, quarterTurns, autoSkew, chosenFilter)
                    owned.add(result.bitmap)
                    if (result.note.isNotBlank()) notes.add("第${index + 1}张 $suffix：${result.note}")
                    currentOutputs.add(savePage(result.bitmap, suffix))
                }
                outputs.addAll(currentOutputs)
            } catch (e: Exception) {
                currentOutputs.forEach { it.delete() }
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures.add("第${index + 1}张：${e.message}")
            } finally { owned.distinct().forEach { it.recycle() } }
        }
        val allOutputs = outputs.toMutableList()
        if (exportPdf && outputs.isNotEmpty()) {
            val pdf = File(directory, "image_${token}_按序合并.pdf")
            try {
                check(pdf.outputStream().use { PdfExporter.exportFiles(outputs, it) }) { "无法生成PDF" }
                allOutputs.add(pdf)
            }
            catch (e: Exception) { pdf.delete(); if (e is kotlinx.coroutines.CancellationException) throw e; failures.add("合并PDF失败：${e.message}") }
        }
        FlowOutcome(buildList {
            add("结果" to "已处理${inputs.size - failures.count { it.startsWith("第") }}/${inputs.size}张，生成${outputs.size}页")
            if (failures.isNotEmpty()) add("失败项" to failures.joinToString("\n"))
            if (notes.isNotEmpty()) add("处理说明" to notes.joinToString("\n"))
        }, allOutputs, outputs)
    }
}

// ======================================================================
// 10. 证件 / 票证结构化抽取
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val kinds = StructureExtractor.Kind.entries
    var kind by rememberToolState(request, "kind") { StructureExtractor.Kind.ID_CARD }
    var text by rememberToolState(request, "text") { "" }
    var precise by rememberToolState(request, "precise") { false }
    var ocrBusy by remember { mutableStateOf(false) }
    var result by rememberToolState<StructureExtractor.Result?>(request, "result") { null }
    var edited by rememberToolState<Map<String, String>>(request, "edited") { emptyMap() }
    var exported by rememberToolState<File?>(request, "exported") { null }
    var exportStatus by rememberToolState(request, "exportStatus") { "" }

    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = exported
        if (uri != null && file != null) scope.launch {
            val savedLabel = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: error("无法创建文件")
                    val label = OutputHistoryStore.describeDestination(context, uri)
                    OutputHistoryStore.markSaved(context, file, uri, label)
                    label
                }.getOrNull()
            }
            exportStatus = savedLabel?.let { "已保存：$it" } ?: "保存失败，请重新选择位置"
        }
    }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    fun run() {
        if (text.isBlank()) {
            toast("先粘入或录入 OCR 文本")
            return
        }
        val r = StructureExtractor.extract(kind, text)
        result = r
        edited = r.fields.associate { it.key to it.value }
    }

    fun export(kindOfExport: String) {
        val r = result ?: return
        val merged = r.copy(fields = r.fields.map { f -> f.copy(value = edited[f.key] ?: f.value) })
        val (name, content) = when (kindOfExport) {
            "json" -> "card_${stamp()}.json" to StructureExtractor.toJson(merged)
            "csv" -> "card_${stamp()}.csv" to StructureExtractor.toCsv(merged)
            else -> "card_${stamp()}.txt" to merged.fields.joinToString("\n") { "${it.label}：${it.value}" }
        }
        val out = File(FileStore.exportDir(context), name)
        out.writeText(content)
        exported = out
        exportStatus = "应用内部结果，尚未保存到手机"
        OutputHistoryStore.recordGenerated(context, out, OutputHistoryStore.mimeFor(out))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("证件票证识别") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { FilePreview(request.files, "输入预览") }
            item {
                Text("证件类型", style = MaterialTheme.typography.titleSmall)
            }
            items(kinds.chunked(3)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k; result = null },
                            label = { Text(k.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !precise, onClick = { precise = false }, label = { Text("普通OCR") })
                    FilterChip(selected = precise, onClick = { precise = true }, label = { Text("高精度OCR") })
                }
                Button(
                    onClick = {
                        if (ocrBusy) return@Button
                        ocrBusy = true
                        scope.launch {
                            val bitmap = withContext(Dispatchers.IO) { ImageIo.loadFromFile(request.files.first(), if (precise) 3600 else 2400) }
                            val recognized = if (bitmap == null) "" else {
                                val engine = PaddleOcrEngine(context)
                                try { engine.recognize(bitmap, precise).text } finally { engine.close(); bitmap.recycle() }
                            }
                            if (recognized.isNotBlank()) text = recognized else toast("没有识别到文字")
                            ocrBusy = false
                        }
                    },
                    enabled = !ocrBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (ocrBusy) "正在识别……" else "从图片识别文字") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("OCR 文本（可手工粘贴）") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    maxLines = 10
                )
                Text(
                    "识别结果可先校正，再抽取证件字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Button(onClick = { run() }, modifier = Modifier.fillMaxWidth()) { Text("抽取字段") }
            }

            val r = result
            if (r != null) {
                item { Text("抽取结果（可直接修改）", style = MaterialTheme.typography.titleMedium) }
                items(r.fields) { f ->
                    Column {
                        OutlinedTextField(
                            value = edited[f.key] ?: f.value,
                            onValueChange = { edited = edited + (f.key to it) },
                            label = {
                                Text(f.label + when (f.valid) {
                                    true -> " ✓"; false -> " ✗"; null -> ""
                                })
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                if (r.notes.isNotEmpty()) {
                    item {
                        Column {
                            r.notes.forEach { n ->
                                Text(n, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { export("json") }) { Text("导出 JSON") }
                        TextButton(onClick = { export("csv") }) { Text("导出 CSV") }
                        TextButton(onClick = { export("txt") }) { Text("导出文本") }
                    }
                }
                exported?.let { file ->
                    item { FilePreview(listOf(file), "导出结果预览") }
                    item {
                        Text(exportStatus, color = if (exportStatus.startsWith("已保存")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveExport.launch(file.name) }) { Text("保存到手机") }
                            TextButton(onClick = { Share.file(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("分享") }
                            TextButton(onClick = { Share.open(context, file, OutputHistoryStore.mimeFor(file)) }) { Text("打开") }
                        }
                    }
                }
                item {
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                r.fields.joinToString("\n") { "${it.label}：${edited[it.key] ?: it.value}" }
                            )
                        )
                        toast("已复制")
                    }) { Text("复制全部") }
                }
                item {
                    Text(
                        "只做字段抽取与校验位自验，不做真伪核验（那需要联网接口，本应用零联网）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ======================================================================
// 兜底
// ======================================================================

@Composable
private fun UnknownFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "存为文档", modifier = modifier,
        config = { Text("该入口暂无专属处理，将按图片存入文档库。") }
    ) { _ ->
        FlowOutcome(listOf("文件数" to "${request.files.size}"), request.files, request.files)
    }
}
