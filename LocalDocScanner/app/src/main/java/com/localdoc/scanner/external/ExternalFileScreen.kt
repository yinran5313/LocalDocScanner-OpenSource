package com.localdoc.scanner.external

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.office.OfficeEngineBridge
import com.localdoc.scanner.office.OfficeEngineInfo
import com.localdoc.scanner.office.OfficeFormat
import com.localdoc.scanner.office.OfficeFormats
import com.localdoc.scanner.office.OpenXmlDocument
import com.localdoc.scanner.office.OpenXmlEditor
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.pdf.printPdf
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalFileScreen(
    external: ExternalFile,
    onClose: () -> Unit,
    onEditPdf: suspend (File, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val officeFormat = remember(external) { OfficeFormats.detect(external.name, external.mime) }
    val displayName = remember(external, officeFormat) { OfficeFormats.ensureExtension(external.name, officeFormat) }
    val resolvedMime = remember(external, officeFormat) {
        officeFormat?.mime ?: external.mime.ifBlank { "application/octet-stream" }
    }
    val extension = displayName.substringAfterLast('.', "").lowercase()
    val isOffice = officeFormat != null
    val isPdf = external.mime == "application/pdf" || extension == "pdf"
    val isImage = external.mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "bmp")
    val isText = !isOffice && (external.mime in setOf("text/plain", "text/csv", "text/markdown") || extension in setOf("txt", "csv", "md", "markdown"))

    var localFile by remember(external) { mutableStateOf<File?>(null) }
    var office by remember(external) { mutableStateOf<OpenXmlDocument?>(null) }
    var textContent by remember(external) { mutableStateOf<String?>(null) }
    var error by remember(external) { mutableStateOf("") }
    var status by remember(external) { mutableStateOf("") }
    var busy by remember(external) { mutableStateOf(true) }
    var retainWorkCopy by remember(external) { mutableStateOf(false) }
    var engineInfo by remember(external) { mutableStateOf(OfficeEngineBridge.installed(context)) }
    var hashBeforeEngine by remember(external) { mutableStateOf("") }
    var textDirty by remember(external) { mutableStateOf(false) }
    var confirmClose by remember(external) { mutableStateOf(false) }
    val edits = remember(external) { mutableStateMapOf<String, String>() }

    BackHandler {
        if (edits.isNotEmpty() || textDirty) confirmClose = true else onClose()
    }

    LaunchedEffect(external) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val safeName = displayName.replace(Regex("[\\/:*?\"<>|]"), "_")
                val root = if (isOffice) File(context.filesDir, "office-work") else File(context.cacheDir, "external-open")
                val dir = File(root, "${System.currentTimeMillis()}-${System.nanoTime()}")
                dir.mkdirs()
                val target = File(dir, "${System.currentTimeMillis()}-$safeName")
                context.contentResolver.openInputStream(external.uri)?.use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: error("无法读取外部文件")
                target
            }
        }
        loaded.onSuccess { file ->
            localFile = file
            when {
                officeFormat?.quickEditSupported == true -> {
                    val parsed = withContext(Dispatchers.IO) { runCatching { OpenXmlEditor.read(file) } }
                    parsed.onSuccess { office = it }.onFailure {
                        status = "快速文字预览不可用，仍可使用完整Office引擎：${it.message}"
                    }
                }
                isText -> {
                    val parsed = withContext(Dispatchers.IO) {
                        runCatching {
                            require(file.length() <= 5L * 1024 * 1024) { "文本文件超过5 MB，暂不在手机内直接编辑" }
                            file.readText(Charsets.UTF_8)
                        }
                    }
                    parsed.onSuccess { textContent = it }.onFailure { error = "文本文件读取失败：${it.message}" }
                }
            }
        }.onFailure { error = it.message ?: "文件读取失败" }
        busy = false
    }

    val latestFile by rememberUpdatedState(localFile)
    val latestRetain by rememberUpdatedState(retainWorkCopy)
    DisposableEffect(external) {
        onDispose { if (!latestRetain) latestFile?.delete() }
    }

    val engineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            busy = true
            delay(500)
            val file = localFile
            if (file != null && file.isFile) {
                val refreshed = withContext(Dispatchers.IO) {
                    val hash = sha256(file)
                    val parsed = if (officeFormat?.quickEditSupported == true) runCatching { OpenXmlEditor.read(file) }.getOrNull() else null
                    hash to parsed
                }
                office = refreshed.second ?: office
                edits.clear()
                retainWorkCopy = true
                OutputHistoryStore.recordGenerated(context, file, resolvedMime)
                status = if (hashBeforeEngine.isNotBlank() && refreshed.first != hashBeforeEngine) {
                    "完整引擎的修改已回到工作副本；请预览后保存到手机。"
                } else {
                    "已从完整引擎返回。工作副本已保留，可再次打开或保存到手机。"
                }
            }
            engineInfo = OfficeEngineBridge.installed(context)
            busy = false
        }
    }

    suspend fun materializeQuickEdits(): File? {
        val source = localFile ?: return null
        if (officeFormat?.quickEditSupported != true || edits.isEmpty()) return source
        val snapshot = edits.toMap()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val output = officeResultFile(context.filesDir, displayName, "快速修改")
                OpenXmlEditor.save(source, output, snapshot)
                output to OpenXmlEditor.read(output)
            }
        }
        return result.fold(
            onSuccess = { (output, parsed) ->
                localFile = output
                office = parsed
                edits.clear()
                retainWorkCopy = true
                output
            },
            onFailure = {
                status = "快速修改写入失败：${it.message}"
                null
            }
        )
    }

    fun openFullEngine() {
        val engine = engineInfo
        if (engine == null) {
            if (!OfficeEngineBridge.openInstallPage(context)) status = "无法打开官方安装页"
            return
        }
        scope.launch {
            busy = true
            val file = materializeQuickEdits()
            if (file == null) {
                busy = false
                return@launch
            }
            retainWorkCopy = true
            OutputHistoryStore.recordGenerated(context, file, resolvedMime)
            hashBeforeEngine = withContext(Dispatchers.IO) { sha256(file) }
            val intent = OfficeEngineBridge.editIntent(context, file, resolvedMime, engine)
            if (OfficeEngineBridge.canResolve(context, intent)) {
                status = "正在进入${engine.label}完整编辑器……"
                busy = false
                engineLauncher.launch(intent)
            } else {
                status = "完整引擎已安装，但没有找到可编辑此格式的页面"
                busy = false
            }
        }
    }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(resolvedMime)) { uri ->
        if (uri != null && localFile != null) scope.launch {
            busy = true
            val quickEdits = edits.toMap()
            val currentText = textContent
            val source = localFile!!
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = when {
                        officeFormat?.quickEditSupported == true && quickEdits.isNotEmpty() -> {
                            officeResultFile(context.filesDir, displayName, "修改").also {
                                OpenXmlEditor.save(source, it, quickEdits)
                            }
                        }
                        isText && currentText != null -> {
                            officeResultFile(context.filesDir, displayName, "修改").also {
                                it.writeText(currentText, Charsets.UTF_8)
                            }
                        }
                        else -> source
                    }
                    context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                        output.inputStream().buffered().use { input -> input.copyTo(stream) }
                    } ?: error("无法创建目标文件")
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    val label = OutputHistoryStore.describeDestination(context, uri)
                    OutputHistoryStore.markSaved(context, output, uri, label)
                    val parsed = if (officeFormat?.quickEditSupported == true) runCatching { OpenXmlEditor.read(output) }.getOrNull() else null
                    Triple(output, parsed, label)
                }
            }
            result.onSuccess { (output, parsed, label) ->
                localFile = output
                office = parsed ?: office
                edits.clear()
                textDirty = false
                retainWorkCopy = true
                status = "已保存：$label"
            }.onFailure { status = "保存失败：${it.message}" }
            busy = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName, maxLines = 1)
                        Text("外部文件 · 原件不会被覆盖", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (edits.isNotEmpty() || textDirty) confirmClose = true else onClose()
                    }) { Text("关闭") }
                }
            )
        },
        bottomBar = {
            when {
                isOffice -> OfficeBottomBar(
                    engine = engineInfo,
                    enabled = !busy && localFile != null,
                    onFullEdit = ::openFullEngine,
                    onSave = {
                        val base = displayName.substringBeforeLast('.', displayName)
                        saveCopy.launch("${base}_完整编辑.$extension")
                    },
                    onShare = { localFile?.let { Share.file(context, it, resolvedMime) } },
                    onDetect = { engineInfo = OfficeEngineBridge.installed(context) }
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (textContent != null) {
                        Button(
                            onClick = {
                                val base = displayName.substringBeforeLast('.', displayName)
                                saveCopy.launch("${base}_修改.$extension")
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("另存修改副本") }
                    }
                    if (isPdf && localFile != null) {
                        Button(
                            onClick = {
                                val file = localFile ?: return@Button
                                scope.launch { onEditPdf(file, displayName) }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("编辑副本") }
                        Button(
                            onClick = { printPdf(context, localFile!!, displayName) },
                            modifier = Modifier.weight(1f)
                        ) { Text("打印") }
                    }
                    TextButton(
                        onClick = { localFile?.let { Share.file(context, it, resolvedMime) } },
                        enabled = localFile != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("分享副本") }
                }
            }
        }
    ) { padding ->
        when {
            busy && localFile == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("正在打开……") }
            error.isNotBlank() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) { Text(error) }
            isOffice && localFile != null -> OfficeWorkspaceBody(
                format = officeFormat!!,
                engine = engineInfo,
                document = office,
                edits = edits,
                status = status,
                file = localFile!!,
                modifier = Modifier.padding(padding)
            )
            textContent != null -> TextEditorBody(
                text = textContent!!,
                onTextChange = { textContent = it; textDirty = true },
                status = status,
                modifier = Modifier.padding(padding)
            )
            isPdf && localFile != null -> PdfViewerBody(localFile!!, Modifier.padding(padding))
            isImage && localFile != null -> AsyncImage(
                model = localFile,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("当前格式还没有内部阅读器")
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("放弃未保存修改？") },
            text = { Text("快速编辑区的修改还没有保存到手机。完整引擎产生并进入导出记录的工作副本不会丢失。") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("放弃并关闭") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun OfficeBottomBar(
    engine: OfficeEngineInfo?,
    enabled: Boolean,
    onFullEdit: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDetect: () -> Unit
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFullEdit, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(if (engine == null) "安装完整引擎" else "完整Office编辑")
            }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f)) { Text("保存到手机") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) { Text("分享工作副本") }
            if (engine == null) TextButton(onClick = onDetect, modifier = Modifier.weight(1f)) { Text("安装后重新检测") }
        }
    }
}

@Composable
private fun OfficeWorkspaceBody(
    format: OfficeFormat,
    engine: OfficeEngineInfo?,
    document: OpenXmlDocument?,
    edits: MutableMap<String, String>,
    status: String,
    file: File,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${format.label} · ${format.family.label}", style = MaterialTheme.typography.titleMedium)
                    Text("工作副本 ${formatOfficeSize(file.length())}")
                    if (engine == null) {
                        Text("未安装完整引擎。请用下方按钮打开官方安装页，安装后返回并重新检测。")
                    } else {
                        Text("完整引擎：${engine.label} ${engine.versionName}")
                        Text("点“完整Office编辑”进入真实排版界面；完成后用返回键回到这里，再保存到手机。")
                        if (!engine.officialFdroidSignature) {
                            Text("当前引擎签名与官方F-Droid版不同，请确认安装来源。", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("外部原件保持不变；完整编辑只修改本App的工作副本。")
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (document != null) {
            item {
                Text("快速文字修改（备用）", style = MaterialTheme.typography.titleMedium)
                Text("这里只改现有文字或单元格；完整排版、公式、图片和对象请进入完整Office编辑。", style = MaterialTheme.typography.bodySmall)
            }
            items(document.units, key = { it.id }) { unit ->
                OutlinedTextField(
                    value = edits[unit.id] ?: unit.text,
                    onValueChange = { value ->
                        if (value == unit.text) edits.remove(unit.id) else edits[unit.id] = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${unit.section} · ${unit.label}") },
                    minLines = if (document.kind == com.localdoc.scanner.office.OfficeKind.WORD) 2 else 1
                )
            }
            if (document.units.isEmpty()) item { Text("快速预览没有找到文字；仍可进入完整Office编辑。") }
        } else item {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "此格式由完整Office引擎负责显示和编辑。主App不尝试拆解旧二进制或ODF文件，以免破坏内容。",
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun TextEditorBody(
    text: String,
    onTextChange: (String) -> Unit,
    status: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("轻文本编辑", style = MaterialTheme.typography.titleMedium)
        Text("支持 TXT、CSV 和 Markdown；保存时另存副本。", style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("内容") }
        )
    }
}

@Composable
private fun PdfViewerBody(file: File, modifier: Modifier = Modifier) {
    val count = remember(file) { PdfTools.pageCount(file) }
    var index by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(file, index) {
        val next = withContext(Dispatchers.IO) { PdfTools.renderPage(file, index) }
        val old = bitmap
        bitmap = next
        old?.takeIf { it !== next }?.recycle()
    }
    DisposableEffect(file) { onDispose { bitmap?.recycle() } }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { index-- }, enabled = index > 0) { Text("上一页") }
            Text("${index + 1} / $count")
            Button(onClick = { index++ }, enabled = index + 1 < count) { Text("下一页") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bitmap == null) Text("正在渲染……")
            else AsyncImage(model = bitmap, contentDescription = "PDF第${index + 1}页", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun officeResultFile(filesDir: File, displayName: String, suffix: String): File {
    val extension = displayName.substringAfterLast('.', "")
    val base = displayName.substringBeforeLast('.', displayName).replace(Regex("[\\/:*?\"<>|]"), "_")
    val dir = File(File(filesDir, "office-results"), "${System.currentTimeMillis()}-${System.nanoTime()}").apply { mkdirs() }
    return File(dir, "${base}_${suffix}${if (extension.isBlank()) "" else ".$extension"}")
}

private fun formatOfficeSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            update(buffer, 0, count)
        }
    }
    digest().joinToString("") { "%02x".format(it) }
}
