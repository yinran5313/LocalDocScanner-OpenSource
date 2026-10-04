package com.localdoc.scanner.ui.tools

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.ToolDrafts
import androidx.compose.runtime.collectAsState
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.structure.StructureExtractor
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import com.localdoc.scanner.ui.components.*

@Composable
internal fun OcrFlow(
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
            val txt = o.copyText.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(txt)) }) { Text("复制") }
            }
        },
        config = { panel() }
    )
}



@Composable
internal fun BarcodeFlow(
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
    )
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val kinds = StructureExtractor.Kind.entries
    var kind by rememberToolState(request, "kind") { StructureExtractor.Kind.ID_CARD }
    var text by rememberToolState(request, "text") { "" }
    var precise by rememberToolState(request, "precise") { true }
    var submitting by remember { mutableStateOf(false) }
    var taskId by remember(request) { mutableStateOf(ToolTasks.latest(context, request)) }
    val tasks by remember { ToolTasks.watch(context) }.collectAsState(emptyList())
    val task = tasks.firstOrNull { it.id == taskId }
    val ocrBusy = submitting || task?.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)
    var appliedTask by rememberToolState(request, "cardAppliedTask") { "" }
    var result by rememberToolState<StructureExtractor.Result?>(request, "result") { null }
    var edited by rememberToolState<Map<String, String>>(request, "edited") { emptyMap() }
    var exported by rememberToolState<File?>(request, "exported") { null }
    var exportStatus by rememberToolState(request, "exportStatus") { "" }

    LaunchedEffect(task?.updatedAt) {
        if (task?.state in setOf(ToolTaskState.SUCCEEDED, ToolTaskState.PARTIAL)) {
            val token = "${task!!.id}:${task.updatedAt}"
            if (appliedTask != token) {
                val outcome = withContext(Dispatchers.IO) { ToolTasks.outcome(context, task.id) }
                outcome?.copyText?.takeIf { it.isNotBlank() }?.let { text = it }
                appliedTask = token
            }
        }
    }
    fun recognize() {
        if (ocrBusy) return
        val parameters = mapOf("precise" to ToolDrafts.gson.toJson(precise))
        submitting = true
        scope.launch {
            try { taskId = ToolTasks.submit(context, request, parameters) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; exportStatus = e.message.orEmpty() }
            finally { submitting = false }
        }
    }

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
        scope.launch {
            try {
                exported = withContext(Dispatchers.IO) {
                    val out = File(FileStore.exportDir(context), name)
                    com.localdoc.scanner.util.AtomicFiles.text(out, content)
                    OutputHistoryStore.recordGenerated(context, out, OutputHistoryStore.mimeFor(out)); out
                }
                exportStatus = "应用内部结果，尚未保存到手机"
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; exportStatus = "导出失败：${e.message}" }
        }
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
                task?.let { ToolTaskPanel(it, onPause = { scope.launch { ToolTasks.pause(context, it.id) } }, onResume = { scope.launch {
                    try { ToolTasks.resume(context, it.id) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; exportStatus = e.message.orEmpty() }
                } }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !precise, onClick = { precise = false }, label = { Text("普通OCR") })
                    FilterChip(selected = precise, onClick = { precise = true }, label = { Text("高精度OCR") })
                }
                Button(
                    onClick = ::recognize,
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
