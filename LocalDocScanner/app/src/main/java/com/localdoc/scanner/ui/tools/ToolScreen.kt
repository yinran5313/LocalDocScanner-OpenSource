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
    val docFiles: List<File> = emptyList()
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
    val formValues: Map<String, String>
)

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

private fun stamp(): String = SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())

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
    key(request.tool.id) {
        when (request.tool.id) {
            "images_to_pdf" -> ImagesToPdfFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_merge" -> PdfMergeFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_split" -> PdfSplitFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_compress" -> PdfCompressFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_to_images" -> PdfToImagesFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_text" -> PdfTextFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_encrypt" -> PdfEncryptFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_office" -> PdfOfficeFlow(request, vm, onBack, onOpenDoc, modifier)
            "ocr" -> OcrFlow(request, vm, onBack, onOpenDoc, modifier)
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
    var stage by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<FlowOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingSaveFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var saveStatus by remember { mutableStateOf("") }
    var showInputAfterProcessing by remember { mutableStateOf(false) }

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
        if (busy) return
        busy = true
        stage = 1
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { process(context) }
                    .getOrElse { FlowOutcome(listOf("出错" to (it.message ?: "处理失败")), emptyList()) }
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
                Text("处理完成", style = MaterialTheme.typography.titleMedium)
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
    var pageSize by remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(1) }

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
        val bitmaps = request.files.mapNotNull { ImageIo.loadFromFile(it, maxSide) }
        val out = File(FileStore.exportDir(ctx), "images_${stamp()}.pdf")
        val size = if (pageSize == 0) PdfExporter.PageSize.A4 else PdfExporter.PageSize.FIT_IMAGE
        val ok = bitmaps.isNotEmpty() && PdfExporter.export(bitmaps, out, size)
        val bytes = if (ok) out.length() else 0L
        bitmaps.forEach { it.recycle() }
        FlowOutcome(
            listOf("页数" to "${bitmaps.size}", "体积" to fmtSize(bytes)),
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
    var order by remember { mutableStateOf(request.files.toList()) }

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
    var mode by remember { mutableIntStateOf(0) }
    var perFile by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("1-1") }

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
    var tier by remember { mutableIntStateOf(1) }

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
    var dpi by remember { mutableIntStateOf(1) }

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
private fun PdfOfficeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val source = request.files.first()
    val operations = listOf("页码水印", "填写文字", "文字标记", "便签", "手写签名", "图片签名", "表单填写", "永久打码")
    var operation by remember { mutableIntStateOf(0) }
    val pageCount = remember(source) { PdfTools.pageCount(source).coerceAtLeast(1) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var footer by remember { mutableStateOf("") }
    var watermark by remember { mutableStateOf("") }
    var addPageNumbers by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(0.22f) }
    var x by remember { mutableFloatStateOf(0.12f) }
    var y by remember { mutableFloatStateOf(0.20f) }
    var width by remember { mutableFloatStateOf(0.42f) }
    var height by remember { mutableFloatStateOf(0.08f) }
    var undoPlacements by remember { mutableStateOf<List<PdfPlacement>>(emptyList()) }
    var redoPlacements by remember { mutableStateOf<List<PdfPlacement>>(emptyList()) }
    var markup by remember { mutableIntStateOf(0) }
    var strokes by remember { mutableStateOf<List<List<PdfInkPoint>>>(emptyList()) }
    var signatureUri by remember { mutableStateOf<Uri?>(null) }
    var watermarkUri by remember { mutableStateOf<Uri?>(null) }
    var pendingEdits by remember { mutableStateOf<List<PendingPdfEdit>>(emptyList()) }
    val formFields = remember(source) { PdfOfficeTools.formFields(source) }
    val formValues = remember(source) { mutableStateMapOf<String, String>() }
    LaunchedEffect(formFields) {
        formFields.forEach { field -> if (field.name !in formValues) formValues[field.name] = field.value }
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
        signatureUri = uri
    }
    val watermarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        watermarkUri = uri
    }

    fun snapshotCurrent() = PendingPdfEdit(
        operation = operation,
        label = if (operation in setOf(1, 2, 3, 4, 5, 7)) "第${pageIndex + 1}页 · ${operations[operation]}" else operations[operation],
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
        formValues = formValues.toMap()
    )

    fun currentCanApply(): Boolean = when (operation) {
        1, 3 -> text.isNotBlank()
        4 -> strokes.any { it.size >= 2 }
        5 -> signatureUri != null
        6 -> formFields.isNotEmpty()
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
                onClick = { pendingEdits = pendingEdits + snapshotCurrent() },
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
                Text("透明度 ${"%.0f".format(opacity * 100)}%")
                Slider(opacity, { opacity = it }, valueRange = 0.08f..0.7f)
            }
            1 -> {
                OutlinedTextField(text, { text = it }, label = { Text("填写到PDF的文字") }, modifier = Modifier.fillMaxWidth())
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
                formFields.take(40).forEach { field ->
                    OutlinedTextField(
                        value = formValues[field.name].orEmpty(),
                        onValueChange = { formValues[field.name] = it },
                        label = { Text("${field.name} · ${field.type}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            7 -> {
                Text("黑框区域会被永久写入栅格页面，原文字和对象不会留在输出PDF中。", color = MaterialTheme.colorScheme.error)
                positionControls()
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
        edits.forEachIndexed { index, edit ->
            if (!ok) return@forEachIndexed
            val target = if (index == edits.lastIndex) out else File(context.cacheDir, "pdf-edit-${System.nanoTime()}-$index.pdf")
            ok = when (edit.operation) {
                0 -> {
                    val bitmap = edit.watermarkUri?.let { ImageIo.loadFromUri(context, it, 1600) }
                    try {
                        PdfOfficeTools.decorate(context, currentInput, target, edit.watermark, edit.header, edit.footer, edit.addPageNumbers, edit.opacity, bitmap)
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
                6 -> edit.formValues.isNotEmpty() && PdfOfficeTools.fillForm(context, currentInput, target, edit.formValues)
                else -> PdfTools.redactPermanent(currentInput, target, edit.pageIndex, edit.x, edit.y, edit.width, edit.height)
            }
            if (currentInput !== source) currentInput.delete()
            if (ok) currentInput = target else target.delete()
        }
        FlowOutcome(
            summary = listOf(
                "操作" to edits.joinToString("、") { it.label },
                "原文件" to fmtSize(source.length()),
                "新文件" to fmtSize(if (ok) out.length() else 0L),
                "结果" to if (ok) "已生成副本" else "处理失败，请检查内容和文件权限"
            ),
            files = if (ok) listOf(out) else emptyList()
        )
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
    var precise by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val engineAvailable = true

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别档位", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("普通(tiny)", "高精度(medium)"), precise) { precise = it }
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
        val text = if (engineAvailable) {
            val source = request.files.firstOrNull()
            val engine = PaddleOcrEngine(ctx)
            val r = try {
                if (source == null) {
                    ""
                } else if (source.extension.equals("pdf", ignoreCase = true)) {
                    val pageCount = PdfTools.pageCount(source)
                    buildString {
                        for (index in 0 until pageCount) {
                            val page = PdfTools.renderPage(source, index, if (precise == 1) 3000 else 2000) ?: continue
                            val recognized = try { engine.recognize(page, precise == 1).text } finally { page.recycle() }
                            if (recognized.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n")
                                append("【第 ${index + 1} 页】\n")
                                append(recognized)
                            }
                        }
                    }
                } else {
                    val bmp = ImageIo.loadFromFile(source, if (precise == 1) 3600 else 2400)
                    if (bmp == null) "" else engine.recognize(bmp, precise == 1).text.also { bmp.recycle() }
                }
            } finally {
                engine.close()
            }
            r
        } else {
            manual
        }
        val out = File(FileStore.exportDir(ctx), "ocr_${stamp()}.txt")
        out.writeText(text)
        FlowOutcome(
            listOf("字数" to "${text.length}", "预览" to text.take(120)),
            listOf(out)
        )
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
            val txt = o.files.firstOrNull()?.readText()?.lineSequence()?.firstOrNull() ?: ""
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(txt)) }) { Text("复制") }
                if (txt.startsWith("http", ignoreCase = true)) {
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
                found += "${r.text}    [${r.barcodeFormat}]"
            }
            bmp.recycle()
        }
        val out = File(FileStore.exportDir(ctx), "barcode_${stamp()}.txt")
        out.writeText(found.joinToString("\n"))
        FlowOutcome(
            if (found.isEmpty()) listOf("结果" to "没识别到条码")
            else found.mapIndexed { i, s -> "结果 ${i + 1}" to s },
            if (found.isEmpty()) emptyList() else listOf(out)
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
    var width by remember { mutableIntStateOf(0) }

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
        val bitmaps = request.files.mapNotNull { ImageIo.loadFromFile(it, 2400) }
        val stitched = Stitch.vertical(bitmaps, maxW)
        val out = File(FileStore.exportDir(ctx), "long_${stamp()}.jpg")
        val ok = stitched != null && ImageIo.saveJpeg(stitched, out, 88)
        val dims = if (stitched != null) "${stitched.width}×${stitched.height}" else "—"
        bitmaps.forEach { it.recycle() }
        stitched?.recycle()
        FlowOutcome(
            listOf("拼接页数" to "${bitmaps.size}", "尺寸" to dims, "体积" to fmtSize(if (ok) out.length() else 0)),
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
    var filter by remember { mutableStateOf(ScanFilter.AUTO) }
    var rotation by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(0) }
    var bookSplit by remember { mutableFloatStateOf(0.5f) }
    var bookHint by remember { mutableStateOf("拖动分割线位置，预览左右页") }
    var flattenBook by remember { mutableStateOf(false) }
    var flattenHint by remember { mutableStateOf("") }
    LaunchedEffect(request.files, mode) {
        if (mode == 1) withContext(Dispatchers.Default) {
            ImageIo.loadFromFile(request.files.first(), 640)?.let { image ->
                try { com.localdoc.scanner.cv.BookGutter.estimate(image) } finally { image.recycle() }
            }
        }?.let { suggestion ->
            bookSplit = suggestion.ratio
            bookHint = if (suggestion.confident) "已找到可能的书缝，请检查左右页预览" else "没有找到明显书缝，请手动调整分割位置"
        }
    }
    var livePreview by remember(request.files) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(request.files.map { it.absolutePath }, filter, rotation, mode, bookSplit, flattenBook) {
        if (mode == 1 && flattenBook) kotlinx.coroutines.delay(250)
        var pendingPreview: Bitmap? = null
        var pendingHint = ""
        try {
        withContext(Dispatchers.Default) {
            pendingPreview =
            when (mode) {
                1 -> {
                    val source = ImageIo.loadFromFile(request.files.first(), if (flattenBook) 4000 else 1400) ?: return@withContext null
                    val owned = mutableListOf(source)
                    try {
                        val (left, right) = DocumentLayouts.splitBookSpread(source, splitRatio = bookSplit)
                        owned.addAll(listOf(left, right))
                        val pages = if (flattenBook) listOf(left, right).map { page ->
                            BookDewarp.flatten(page).also { owned.add(it.bitmap) }
                        } else emptyList()
                        pendingHint = pages.mapIndexed { index, page -> "${if (index == 0) "左页" else "右页"}：${page.note}" }.joinToString("\n")
                        Stitch.vertical(if (flattenBook) pages.map { it.bitmap } else listOf(left, right), 900)
                    } finally { owned.forEach { it.recycle() } }
                }
                2 -> {
                    val front = request.files.getOrNull(0)?.let { ImageIo.loadFromFile(it, 1200) }
                    val back = request.files.getOrNull(1)?.let { ImageIo.loadFromFile(it, 1200) }
                    if (front == null || back == null) {
                        front?.recycle(); back?.recycle(); null
                    } else DocumentLayouts.idCardSheet(front, back).also { front.recycle(); back.recycle() }
                }
                else -> {
                    val source = ImageIo.loadFromFile(request.files.first(), 1400) ?: return@withContext null
                    val rotated = ImageIo.rotate(source, rotation * 90f)
                    if (rotated !== source) source.recycle()
                    val filtered = applyFilter(rotated, filter)
                    if (filtered !== rotated) rotated.recycle()
                    filtered
                }
            }
        }
        val next = pendingPreview
        livePreview?.takeIf { it !== next }?.recycle()
        livePreview = next
        pendingPreview = null
        flattenHint = pendingHint
        } finally { pendingPreview?.recycle() }
    }
    DisposableEffect(Unit) { onDispose { livePreview?.recycle() } }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("处理方式", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("编辑图片", "书本双页拆分", "证件双面拼版"), mode) { mode = it }
            if (mode == 1) {
                Text(bookHint)
                Text("左页 ${ (bookSplit * 100).toInt() }% · 右页 ${ ((1f - bookSplit) * 100).toInt() }%")
                Slider(value = bookSplit, onValueChange = { bookSplit = it }, valueRange = .2f.. .8f)
                Text("调整书缝位置，拆分后的左右页可以分别裁边。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("按文字行曲率展平")
                    Switch(checked = flattenBook, onCheckedChange = { flattenBook = it })
                }
                if (flattenBook) Text(flattenHint.ifBlank { "正在建立左右页展平模型…" }, style = MaterialTheme.typography.bodySmall)
            }
            if (mode == 2 && request.files.size < 2) {
                Text("证件双面拼版需要一次选择正反面两张图片。", color = MaterialTheme.colorScheme.error)
            }
            if (mode == 0) {
            Text("滤镜", style = MaterialTheme.typography.titleSmall)
            ChipRow(ScanFilter.entries.map { it.label }, filter.ordinal) { filter = ScanFilter.entries[it] }
            Text("旋转", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("0°", "90°", "180°", "270°"), rotation) { rotation = it }
            }
            Text("实时处理预览", style = MaterialTheme.typography.titleSmall)
            if (livePreview == null) Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { Text("正在生成预览…") }
            else AsyncImage(model = livePreview, contentDescription = "图片处理预览", modifier = Modifier.fillMaxWidth().height(300.dp))
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "保存", showInputPreview = false, modifier = modifier, config = { panel() }
    ) { ctx ->
        if (mode == 1) {
            val source = ImageIo.loadFromFile(request.files.first(), 4000)
            if (source == null) return@ToolFlow FlowOutcome(listOf("结果" to "图片读取失败"), emptyList())
            val owned = mutableListOf(source)
            try {
            val (left, right) = DocumentLayouts.splitBookSpread(source, splitRatio = bookSplit)
            owned.addAll(listOf(left, right))
            val pages = if (flattenBook) listOf(left, right).map { page ->
                BookDewarp.flatten(page).also { owned.add(it.bitmap) }
            } else emptyList()
            val leftFile = File(FileStore.exportDir(ctx), "book_${stamp()}_左页.jpg")
            val rightFile = File(FileStore.exportDir(ctx), "book_${stamp()}_右页.jpg")
            val ok = ImageIo.saveJpeg(pages.getOrNull(0)?.bitmap ?: left, leftFile, 94) &&
                ImageIo.saveJpeg(pages.getOrNull(1)?.bitmap ?: right, rightFile, 94)
            val flattenSummary = pages.mapIndexed { index, page -> "${if (index == 0) "左页" else "右页"}：${page.note}" }.joinToString("；")
            if (!ok) { leftFile.delete(); rightFile.delete() }
            return@ToolFlow FlowOutcome(
                listOf("结果" to if (ok) "已拆为左右2页${if (flattenBook) "；$flattenSummary" else ""}" else "保存失败"),
                if (ok) listOf(leftFile, rightFile) else emptyList(),
                if (ok) listOf(leftFile, rightFile) else emptyList()
            )
            } finally { owned.forEach { it.recycle() } }
        }
        if (mode == 2) {
            if (request.files.size < 2) return@ToolFlow FlowOutcome(listOf("结果" to "请重新选择正反面两张图片"), emptyList())
            val front = ImageIo.loadFromFile(request.files[0], 2400)
            val back = ImageIo.loadFromFile(request.files[1], 2400)
            if (front == null || back == null) {
                front?.recycle(); back?.recycle()
                return@ToolFlow FlowOutcome(listOf("结果" to "图片读取失败"), emptyList())
            }
            val sheet = DocumentLayouts.idCardSheet(front, back)
            val out = File(FileStore.exportDir(ctx), "证件双面_${stamp()}.jpg")
            val ok = ImageIo.saveJpeg(sheet, out, 94)
            front.recycle(); back.recycle(); sheet.recycle()
            return@ToolFlow FlowOutcome(
                listOf("结果" to if (ok) "已生成双面拼版" else "保存失败"),
                if (ok) listOf(out) else emptyList(),
                if (ok) listOf(out) else emptyList()
            )
        }
        val src = ImageIo.loadFromFile(request.files.first(), 2400)
        var bmp: Bitmap? = src
        bmp = src?.let { ImageIo.rotate(it, rotation * 90f) }
        if (bmp != null && bmp !== src) src?.recycle()
        val filtered = bmp?.let { applyFilter(it, filter) } ?: bmp
        val out = File(FileStore.exportDir(ctx), "edit_${stamp()}.jpg")
        val ok = filtered != null && ImageIo.saveJpeg(filtered, out, 92)
        val dims = if (filtered != null) "${filtered.width}×${filtered.height}" else "—"
        if (filtered != null && filtered !== bmp) filtered.recycle()
        bmp?.recycle()
        FlowOutcome(
            listOf("尺寸" to dims, "体积" to fmtSize(if (ok) out.length() else 0)),
            if (ok) listOf(out) else emptyList(),
            if (ok) listOf(out) else emptyList()
        )
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
    var kind by remember { mutableStateOf(StructureExtractor.Kind.ID_CARD) }
    var text by remember { mutableStateOf("") }
    var precise by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StructureExtractor.Result?>(null) }
    var edited by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var exported by remember { mutableStateOf<File?>(null) }
    var exportStatus by remember { mutableStateOf("") }

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
