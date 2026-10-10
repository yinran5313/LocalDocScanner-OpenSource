package com.localdoc.scanner.ui.doc

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.localdoc.scanner.ui.components.WrappingOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.data.db.PageEntity
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.export.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.localdoc.scanner.ocr.OcrNaming
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.util.Share
import com.localdoc.scanner.jobs.OcrJobs
import kotlinx.coroutines.launch
import java.io.File

private enum class ExportFormat { PDF, JPG }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocDetailScreen(
    docId: String,
    vm: AppViewModel,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onEditPage: (PageEntity, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val snackbar = remember { SnackbarHostState() }

    var refresh by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    LaunchedEffect(docId, refresh, doc?.updatedAt) { pages = vm.pages(docId) }

    var viewerIndex by remember { mutableIntStateOf(-1) }
    var renaming by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var batchOpen by remember { mutableStateOf(false) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchFilter by remember { mutableStateOf(ScanFilter.AUTO) }
    var batchBrightness by remember { mutableFloatStateOf(0f) }
    var batchContrast by remember { mutableFloatStateOf(1f) }
    var lineEditPage by remember { mutableStateOf<PageEntity?>(null) }
    var lineEditIndex by remember { mutableIntStateOf(0) }
    var lineEditText by remember { mutableStateOf("") }
    var ocrOpen by remember { mutableStateOf(false) }
    var ocrPrecise by remember { mutableStateOf(context.getSharedPreferences("ocr_job_receipts", android.content.Context.MODE_PRIVATE).getBoolean("$docId:precise", true)) }
    var ocrLanguage by remember { mutableStateOf(context.getSharedPreferences("ocr_job_receipts", android.content.Context.MODE_PRIVATE).getString("$docId:language", "AUTO") ?: "AUTO") }
    var ocrRunning by remember { mutableStateOf(false) }
    var ocrText by remember(doc?.ocrText) { mutableStateOf(doc?.ocrText.orEmpty()) }
    var ocrStats by remember { mutableStateOf("") }
    var ocrNameSuggestion by remember { mutableStateOf("") }
    var confirmDeleteDoc by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var pageSize by remember { mutableStateOf(com.localdoc.scanner.data.AppPreferences(context).pageSize) }
    var pdfMaxImageSide by remember { mutableIntStateOf(com.localdoc.scanner.data.AppPreferences(context).imageSide) }
    var searchablePdf by remember { mutableStateOf(false) }
    var exportName by remember(doc?.title) { mutableStateOf(doc?.title ?: "文档") }
    var pageBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val ocrWorks by remember(context, docId) { OcrJobs.observe(context, docId) }.collectAsState(emptyList())
    val ocrWork = ocrWorks.firstOrNull { it.id.toString() == context.getSharedPreferences("ocr_job_receipts", android.content.Context.MODE_PRIVATE).getString("$docId:work", null) }
    LaunchedEffect(ocrWork) {
        if (ocrWork != null) {
            ocrRunning = !ocrWork.state.isFinished
            val data = if (ocrWork.state.isFinished) ocrWork.outputData else ocrWork.progress
            ocrStats = when (ocrWork.state) {
                androidx.work.WorkInfo.State.CANCELLED -> "已暂停；已完成页面保留，可续跑"
                androidx.work.WorkInfo.State.ENQUEUED -> "等待系统调度；已完成 ${data.getInt("done", 0)} 页"
                else -> "${if (ocrRunning) "识别中" else "本次结束"}：${data.getInt("done", 0)}/${data.getInt("total", pages.size)} 页" +
                    data.getString("error").orEmpty().takeIf(String::isNotBlank)?.let { "；$it" }.orEmpty()
            }
            if (ocrWork.state.isFinished) {
                ocrText = vm.docs.value.firstOrNull { it.id == docId }?.ocrText.orEmpty()
                ocrNameSuggestion = OcrNaming.suggest(ocrText, doc?.title ?: "扫描文档")
                refresh++
            }
        }
    }
    fun startOcr(resume: Boolean) {
        ocrRunning = true
        scope.launch {
            try { OcrJobs.start(context, docId, ocrPrecise, resume, com.localdoc.scanner.ocr.OcrLanguage.fromCode(ocrLanguage)); ocrStats = "已提交，系统将继续处理" }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; ocrRunning = false; ocrStats = e.message ?: "提交失败" }
        }
    }

    fun changePage(action: suspend () -> Unit) {
        if (pageBusy) return
        pageBusy = true
        scope.launch { try { action(); refresh++ }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("页面操作失败：${e.message}") }
            finally { pageBusy = false }
        }
    }
    fun cleanName(value: String): String = value.ifBlank { "文档" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    fun toast(value: String) = Toast.makeText(context, value, Toast.LENGTH_LONG).show()

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = if (searchablePdf) vm.exportSearchablePdf(docId, uri, pageSize, pdfMaxImageSide)
            else vm.exportPdf(docId, uri, pageSize, pdfMaxImageSide)
            busy = false
            if (ok) {
                val label = OutputHistoryStore.describeDestination(context, uri)
                OutputHistoryStore.recordDirectSaved(context, "${cleanName(exportName)}.pdf", "application/pdf", uri, label)
                toast("已保存：$label")
            } else toast("PDF保存失败")
        }
    }
    val saveJpegs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val count = vm.exportJpegs(docId, uri, cleanName(exportName))
            busy = false
            if (count > 0) {
                val label = OutputHistoryStore.describeDestination(context, uri)
                OutputHistoryStore.recordDirectSaved(
                    context,
                    "${cleanName(exportName)}（$count 张JPG）",
                    "vnd.android.document/directory",
                    uri,
                    label
                )
                toast("已保存 $count 张JPG：$label")
            } else toast("JPG保存失败")
        }
    }
    val saveOcrText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(ocrText)
                } ?: error("无法创建文件")
            }.isSuccess
            toast(if (ok) "识别文字已保存到你选择的位置" else "文字保存失败")
        }
    }

    fun saveDefaultExport() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val files = if (exportFormat == ExportFormat.PDF) {
                    val file = File(FileStore.exportDir(context), "${cleanName(exportName)}_${System.currentTimeMillis()}.pdf")
                    val ok = if (searchablePdf) vm.exportSearchablePdf(docId, file, pageSize, pdfMaxImageSide)
                        else vm.exportPdf(docId, file, pageSize, pdfMaxImageSide)
                    check(ok) { "PDF生成失败" }
                    OutputHistoryStore.recordGenerated(context, file, "application/pdf")
                    listOf(file)
                } else vm.renderedFiles(docId)
                check(files.isNotEmpty()) { "没有可保存页面" }
                val labels = withContext(Dispatchers.IO) { files.map { com.localdoc.scanner.output.DefaultDestination.save(context, it) } }
                toast("已保存：" + labels.joinToString("；"))
                exportOpen = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("保存失败：${e.message}")
            } finally { busy = false }
        }
    }

    fun shareExport() {
        if (busy) return
        scope.launch {
            busy = true
            if (exportFormat == ExportFormat.PDF) {
                val file = File(FileStore.exportDir(context), "${cleanName(exportName)}.pdf")
                val ok = if (searchablePdf) vm.exportSearchablePdf(docId, file, pageSize, pdfMaxImageSide)
                else vm.exportPdf(docId, file, pageSize, pdfMaxImageSide)
                if (ok) {
                    OutputHistoryStore.recordGenerated(context, file, "application/pdf")
                    Share.file(context, file, "application/pdf")
                } else toast("PDF生成失败")
            } else {
                val files = vm.renderedFiles(docId)
                if (files.isNotEmpty()) Share.files(context, files, "image/jpeg") else toast("没有可分享的页面")
            }
            busy = false
            exportOpen = false
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc?.title ?: "文档", style = MaterialTheme.typography.titleMedium)
                        Text(if (ocrRunning) "OCR处理中 · 可退出此页" else "${pages.size} 页 · 已保存到文档库", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { ocrOpen = true }) { Text("文字") }
                    TextButton(onClick = { manageOpen = true }) { Text("管理") }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onAddPage, modifier = Modifier.weight(1f)) { Text("添加页面") }
                Button(onClick = { exportOpen = true }, enabled = pages.isNotEmpty() && !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "处理中…" else "导出/分享")
                }
            }
        }
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这份文档还没有页面")
                    TextButton(onClick = onAddPage) { Text("添加页面") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                    Card(onClick = { viewerIndex = index }) {
                        Column {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                                AsyncImage(
                                    model = File(page.filePath),
                                    contentDescription = "第 ${index + 1} 页",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("${index + 1}", modifier = Modifier.padding(6.dp), color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextButton(onClick = { changePage { vm.movePage(docId, index, index - 1) } }, enabled = !pageBusy && index > 0) { Text("前移") }
                                TextButton(onClick = { changePage { vm.movePage(docId, index, index + 1) } }, enabled = !pageBusy && index < pages.lastIndex) { Text("后移") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextButton(onClick = { onEditPage(page, index) }) { Text("编辑") }
                                TextButton(onClick = { changePage { vm.duplicatePage(docId, page.id) } }, enabled = !pageBusy) { Text("复制") }
                                TextButton(onClick = {
                                    scope.launch {
                                        vm.trashPage(docId, page.id)
                                        refresh++
                                        if (snackbar.showSnackbar("已删除第 ${index + 1} 页", actionLabel = "撤销") == SnackbarResult.ActionPerformed) {
                                            vm.restorePage(docId, page.id)
                                            refresh++
                                        }
                                    }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var value by remember { mutableStateOf(doc?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名文档") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { renaming = false; scope.launch { vm.rename(docId, value.ifBlank { "未命名文档" }) } }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } }
        )
    }

    if (manageOpen) {
        AlertDialog(
            onDismissRequest = { manageOpen = false },
            title = { Text("管理文档") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { manageOpen = false; renaming = true }, modifier = Modifier.fillMaxWidth()) { Text("重命名") }
                    Button(onClick = { manageOpen = false; organizing = true }, modifier = Modifier.fillMaxWidth()) { Text("文件夹和标签") }
                    Button(onClick = { manageOpen = false; batchOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("批量增强全部页面") }
                    TextButton(onClick = { manageOpen = false; confirmDeleteDoc = true }, modifier = Modifier.fillMaxWidth()) { Text("移到回收站") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { manageOpen = false }) { Text("关闭") } }
        )
    }

    if (batchOpen) {
        AlertDialog(
            onDismissRequest = { if (!batchRunning) batchOpen = false },
            title = { Text("批量增强 ${pages.size} 页") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanFilter.entries.chunked(3).forEach { row ->
                        WrappingOptions {
                            row.forEach { option ->
                                FilterChip(
                                    selected = batchFilter == option,
                                    onClick = { batchFilter = option },
                                    label = { Text(option.label) },
                                    enabled = !batchRunning
                                )
                            }
                        }
                    }
                    Text("亮度 ${"%.2f".format(batchBrightness)}")
                    Slider(
                        value = batchBrightness,
                        onValueChange = { batchBrightness = it },
                        valueRange = -0.45f..0.45f,
                        enabled = !batchRunning
                    )
                    Text("对比度 ${"%.2f".format(batchContrast)}")
                    Slider(
                        value = batchContrast,
                        onValueChange = { batchContrast = it },
                        valueRange = 0.65f..1.55f,
                        enabled = !batchRunning
                    )
                    Text("只复制增强参数，各页仍使用自己的裁边；已有OCR结果会清空，避免搜索到旧文字。", style = MaterialTheme.typography.bodySmall)
                    if (batchRunning) Text("正在逐页重新生成……", color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        batchRunning = true
                        scope.launch {
                            val result = vm.applyEnhancementToAll(docId, batchFilter, batchBrightness, batchContrast)
                            batchRunning = false
                            batchOpen = false
                            refresh++
                            toast(
                                "已处理 ${result.succeededPages}/${result.pageCount} 页" +
                                    if (result.failedPages.isEmpty()) "" else "，失败页：${result.failedPages.joinToString()}"
                            )
                        }
                    },
                    enabled = !batchRunning && pages.isNotEmpty()
                ) { Text("应用到全部页面") }
            },
            dismissButton = { TextButton(onClick = { batchOpen = false }, enabled = !batchRunning) { Text("取消") } }
        )
    }

    if (organizing) {
        var folder by remember { mutableStateOf(doc?.folder.orEmpty()) }
        var tags by remember { mutableStateOf(doc?.tags.orEmpty()) }
        AlertDialog(
            onDismissRequest = { organizing = false },
            title = { Text("文件夹和标签") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(folder, { folder = it }, label = { Text("文件夹") }, singleLine = true)
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签，用逗号分开") }, singleLine = true)
                    Text("首页搜索会同时查找文件夹、标签和识别文字。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    organizing = false
                    scope.launch { vm.setOrganization(docId, folder, tags) }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { organizing = false }) { Text("取消") } }
        )
    }

    if (ocrOpen) {
        AlertDialog(
            onDismissRequest = { ocrOpen = false },
            title = { Text("本地识别文字") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WrappingOptions {
                        FilterChip(
                            selected = !ocrPrecise,
                            onClick = { ocrPrecise = false },
                            label = { Text("普通·更快") },
                            enabled = !ocrRunning
                        )
                        FilterChip(
                            selected = ocrPrecise,
                            onClick = { ocrPrecise = true },
                            label = { Text("高精度 · medium") },
                            enabled = !ocrRunning
                        )
                    }
                    com.localdoc.scanner.ui.tools.OcrLanguagePicker(ocrLanguage) { ocrLanguage = it }
                    Text("识别在手机本地完成；高精度会更慢、占用更多内存。", style = MaterialTheme.typography.bodySmall)
                    if (ocrRunning) Text("可关闭此页；系统重启后会从已保存的页面继续。强制停止应用后需重新打开。", color = MaterialTheme.colorScheme.primary)
                    if (ocrStats.isNotBlank()) Text(ocrStats, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = ocrText,
                        onValueChange = { ocrText = it },
                        label = { Text("识别结果，可直接校正") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        enabled = !ocrRunning
                    )
                    WrappingOptions {
                        TextButton(
                            onClick = { startOcr(false) },
                            enabled = !ocrRunning && pages.isNotEmpty()
                        ) { Text(if (ocrText.isBlank()) "开始识别" else "重新识别") }
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(ocrText)) },
                            enabled = ocrText.isNotBlank()
                        ) { Text("复制") }
                        TextButton(
                            onClick = { saveOcrText.launch("${cleanName(doc?.title ?: "文档")}_文字.txt") },
                            enabled = ocrText.isNotBlank()
                        ) { Text("另存TXT") }
                    }
                    if (!doc?.legacyOcrText.isNullOrBlank()) TextButton(onClick = {
                        clipboard.setText(AnnotatedString(doc!!.legacyOcrText)); toast("旧版全文备份已复制，原文仍保留")
                    }) { Text("复制旧版全文备份") }
                    Text("下面的全文是独立校订稿。修正可搜索PDF文字层，请逐页校正原识别行。", style = MaterialTheme.typography.bodySmall)
                    pages.forEachIndexed { pageIndex, page ->
                        val lines = com.localdoc.scanner.ocr.OcrLayout.decode(page.ocrLayout)
                        if (lines.isNotEmpty()) Row {
                            Text("第${pageIndex + 1}页")
                            TextButton(onClick = { lineEditPage = page; lineEditIndex = 0; lineEditText = lines[0].text }, enabled = !ocrRunning) { Text("逐行校正文字层") }
                        }
                    }
                    WrappingOptions {
                        TextButton(onClick = { OcrJobs.cancel(context, docId) }, enabled = ocrRunning) { Text("暂停") }
                        TextButton(onClick = { startOcr(true) }, enabled = !ocrRunning && ocrWork != null) { Text("续跑 / 重试失败页") }
                    }
                    if (ocrNameSuggestion.isNotBlank() && ocrNameSuggestion != doc?.title) {
                        TextButton(
                            onClick = {
                                val suggested = ocrNameSuggestion
                                scope.launch { vm.rename(docId, suggested); refresh++ }
                                ocrNameSuggestion = ""
                            },
                            enabled = !ocrRunning
                        ) { Text("建议命名：$ocrNameSuggestion") }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { vm.updateDocumentOcrText(docId, ocrText) }
                        ocrOpen = false
                    },
                    enabled = !ocrRunning
                ) { Text("保存独立校订稿") }
            },
            dismissButton = {
                TextButton(onClick = { ocrOpen = false }) { Text("关闭") }
            }
        )
    }

    lineEditPage?.let { page ->
        val lines = com.localdoc.scanner.ocr.OcrLayout.decode(page.ocrLayout)
        AlertDialog(onDismissRequest = { lineEditPage = null }, title = { Text("校正PDF文字层 · 第${lineEditIndex + 1}/${lines.size}行") },
            text = { Column {
                AsyncImage(model = File(page.filePath), contentDescription = "原页面", modifier = Modifier.fillMaxWidth().height(200.dp))
                OutlinedTextField(lineEditText, { lineEditText = it }, label = { Text("此行文字，保留原坐标") })
                Row {
                    TextButton(onClick = { lineEditIndex--; lineEditText = lines[lineEditIndex].text }, enabled = lineEditIndex > 0) { Text("上一行") }
                    TextButton(onClick = { lineEditIndex++; lineEditText = lines[lineEditIndex].text }, enabled = lineEditIndex + 1 < lines.size) { Text("下一行") }
                }
            } }, confirmButton = { Button(onClick = { scope.launch {
                try { vm.correctOcrLine(page.id, lineEditIndex, lineEditText, page.updatedAt); refresh++; lineEditPage = null; toast("文字层已校正，下次导出生效") }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; toast(e.message ?: "校正失败") }
            } }) { Text("保存此行") } }, dismissButton = { TextButton(onClick = { lineEditPage = null }) { Text("取消") } })
    }
    if (confirmDeleteDoc) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDoc = false },
            title = { Text("移到回收站？") },
            text = { Text("可以在首页的回收站恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteDoc = false; scope.launch { vm.trash(docId); onBack() } }) { Text("移到回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDoc = false }) { Text("取消") } }
        )
    }

    if (exportOpen) {
        AlertDialog(
            onDismissRequest = { if (!busy) exportOpen = false },
            title = { Text("导出 ${pages.size} 页") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("输出预览", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pages.size) { index ->
                            AsyncImage(
                                model = File(pages[index].filePath),
                                contentDescription = "输出第 ${index + 1} 页",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.width(96.dp).height(132.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                    OutlinedTextField(exportName, { exportName = it }, label = { Text("文件名") }, singleLine = true)
                    WrappingOptions {
                        FilterChip(exportFormat == ExportFormat.PDF, { exportFormat = ExportFormat.PDF }, label = { Text("PDF") })
                        FilterChip(exportFormat == ExportFormat.JPG, { exportFormat = ExportFormat.JPG }, label = { Text("JPG图片") })
                    }
                    if (exportFormat == ExportFormat.PDF) {
                        FilterChip(
                            selected = searchablePdf,
                            onClick = { searchablePdf = !searchablePdf },
                            enabled = pages.any { it.ocrText.isNotBlank() },
                            label = { Text("可搜索PDF") }
                        )
                        if (pages.none { it.ocrText.isNotBlank() }) {
                            Text("先在“文字”中完成OCR，才能生成可搜索PDF。", style = MaterialTheme.typography.bodySmall)
                        }
                        WrappingOptions {
                            FilterChip(pageSize == PdfExporter.PageSize.A4, { pageSize = PdfExporter.PageSize.A4 }, label = { Text("A4页面") })
                            FilterChip(pageSize == PdfExporter.PageSize.LETTER, { pageSize = PdfExporter.PageSize.LETTER }, label = { Text("Letter") })
                            FilterChip(pageSize == PdfExporter.PageSize.FIT_IMAGE, { pageSize = PdfExporter.PageSize.FIT_IMAGE }, label = { Text("适合图片") })
                        }
                        WrappingOptions {
                            FilterChip(pdfMaxImageSide == 2000, { pdfMaxImageSide = 2000 }, label = { Text("标准质量") })
                            FilterChip(pdfMaxImageSide == 3200, { pdfMaxImageSide = 3200 }, label = { Text("高清") })
                        }
                    }
                    Text(
                        if (exportFormat == ExportFormat.PDF) "将生成1个PDF，共${pages.size}页。" else "将生成${pages.size}张JPG，顺序与当前页面一致。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    exportOpen = false
                    if (exportFormat == ExportFormat.PDF) savePdf.launch("${cleanName(exportName)}.pdf") else saveJpegs.launch(null)
                }, enabled = !busy) { Text("保存到手机") }
            },
            dismissButton = {
                Column {
                    if (com.localdoc.scanner.data.AppPreferences(context).text("destination").isNotBlank()) TextButton(onClick = ::saveDefaultExport, enabled = !busy) { Text("保存到默认文件夹") }
                    TextButton(onClick = { shareExport() }, enabled = !busy) { Text("分享") }
                    TextButton(onClick = { exportOpen = false }, enabled = !busy) { Text("取消") }
                }
            }
        )
    }

    if (viewerIndex in pages.indices) {
        PageViewer(
            pages = pages,
            initialIndex = viewerIndex,
            onClose = { viewerIndex = -1 },
            onEdit = { page, index -> viewerIndex = -1; onEditPage(page, index) }
        )
    }
}

@Composable
private fun PageViewer(
    pages: List<PageEntity>,
    initialIndex: Int,
    onClose: () -> Unit,
    onEdit: (PageEntity, Int) -> Unit
) {
    var index by remember { mutableIntStateOf(initialIndex.coerceIn(pages.indices)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF080A0D))) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
                Text("${index + 1} / ${pages.size}", color = Color.White)
                TextButton(onClick = { onEdit(pages[index], index) }) { Text("编辑", color = Color.White) }
            }
            ZoomablePage(File(pages[index].filePath), Modifier.weight(1f).fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { index-- }, enabled = index > 0) { Text("上一页") }
                Button(onClick = { index++ }, enabled = index < pages.lastIndex) { Text("下一页") }
            }
        }
    }
}

@Composable
private fun ZoomablePage(file: File, modifier: Modifier = Modifier) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(file) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    })
                }
        )
    }
}
