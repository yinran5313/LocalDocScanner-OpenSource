package com.localdoc.scanner.ui.tools

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.ToolDrafts
import androidx.compose.runtime.collectAsState
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.pdf.PdfInkPoint
import com.localdoc.scanner.pdf.PdfMarkup
import com.localdoc.scanner.pdf.PdfOfficeTools
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.localdoc.scanner.ui.components.*

private data class PdfPlacement(val x: Float, val y: Float, val width: Float, val height: Float)

@Composable
internal fun PdfSignFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
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
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, runLabel = "生成数字签名PDF", taskPassword = { password.toCharArray() }, config = {
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
    })
}


@Composable
internal fun PdfOfficeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val source = request.files.first()
    val operations = listOf("页码水印", "填写文字", "文字标记", "便签", "手写签名", "图片签名", "表单填写", "永久打码", "管理批注", "修改原文字")
    var sensitiveRegions by rememberToolState<List<com.localdoc.scanner.pdf.SensitiveRegion>>(request, "sensitiveRegions") { emptyList() }
    var sensitiveKeywords by rememberToolState(request, "sensitiveKeywords") { "" }
    var sensitiveStatus by rememberToolState(request, "sensitiveStatus") { "" }
    val sensitiveScope = rememberCoroutineScope()
    var sensitiveSubmitting by remember { mutableStateOf(false) }
    var sensitiveId by remember(request) { mutableStateOf(ToolTasks.latest(context, request, "sensitive")) }
    val sensitiveTasks by remember { ToolTasks.watch(context) }.collectAsState(emptyList())
    val sensitiveTask = sensitiveTasks.firstOrNull { it.id == sensitiveId }
    val detectingSensitive = sensitiveSubmitting || sensitiveTask?.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)
    var sensitiveApplied by rememberToolState(request, "sensitiveApplied") { "" }
    LaunchedEffect(sensitiveTask?.updatedAt) {
        if (sensitiveTask?.state == ToolTaskState.SUCCEEDED) {
            val token = "${sensitiveTask.id}:${sensitiveTask.updatedAt}"
            if (sensitiveApplied != token) {
                val outcome = withContext(Dispatchers.IO) { ToolTasks.outcome(context, sensitiveTask.id) }
                val found = outcome?.sensitiveRegions.orEmpty()
                val page = withContext(Dispatchers.IO) { ToolTasks.spec(context, sensitiveTask.id).parameters["pageIndex"]?.toIntOrNull() ?: 0 }
                sensitiveRegions = sensitiveRegions.filter { it.page != page } + found
                sensitiveStatus = "第${page + 1}页发现${found.size}个候选，请确认。"
                sensitiveApplied = token
            }
        }
    }
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
        formValues = formValues.filterKeys { name -> formFields.any { it.name == name && !it.readOnly && it.type != "PDSignatureField" } },
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
                    val parameters = mapOf("pageIndex" to ToolDrafts.gson.toJson(pageIndex), "keywords" to ToolDrafts.gson.toJson(
                        sensitiveKeywords.split(',', '，').map(String::trim).filter(String::isNotEmpty)))
                    sensitiveSubmitting = true
                    sensitiveScope.launch {
                        try { sensitiveId = ToolTasks.submit(context, request, parameters, action = "sensitive") }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; sensitiveStatus = "提交失败：${e.message}" }
                        finally { sensitiveSubmitting = false }
                    }
                }, enabled = !detectingSensitive) { Text(if (detectingSensitive) "后台识别中…" else "查找本页敏感信息") }
                sensitiveTask?.let { task -> ToolTaskPanel(task, onPause = { sensitiveScope.launch { ToolTasks.pause(context, task.id) } }, onResume = { sensitiveScope.launch {
                    try { ToolTasks.resume(context, task.id) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; sensitiveStatus = e.message.orEmpty() }
                } }) }
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
        config = { panel() },
        extraParameters = { mapOf("edits" to ToolDrafts.gson.toJson(if (pendingEdits.isEmpty()) listOf(snapshotCurrent()) else pendingEdits)) }
    )
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
