package com.localdoc.scanner.ui.tools

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.ToolDrafts
import androidx.compose.runtime.collectAsState
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedIconButton
import com.localdoc.scanner.R
import com.localdoc.scanner.ui.components.*

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolFlow(
    request: ToolRequest,
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    runLabel: String = "开始处理",
    showInputPreview: Boolean = true,
    resultExtra: @Composable (FlowOutcome) -> Unit = {},
    config: @Composable () -> Unit,
    extraParameters: () -> Map<String, String> = { emptyMap() },
    taskPassword: () -> CharArray? = { null }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by rememberToolState(request, "stage") { 0 }
    var outcome by remember(request) { mutableStateOf<FlowOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expandedInputPreview by remember(request) { mutableStateOf(false) }
    var pendingSaveFiles by rememberToolState<List<File>>(request, "pendingSaveFiles") { emptyList() }
    var saveStatus by rememberToolState(request, "saveStatus") { "" }
    var showInputAfterProcessing by rememberToolState(request, "showInputAfterProcessing") { false }

    var taskId by remember(request) { mutableStateOf(ToolTasks.latest(context, request)) }
    val tasks by remember(context) { ToolTasks.watch(context) }.collectAsState(emptyList())
    val task = tasks.firstOrNull { it.id == taskId }
    LaunchedEffect(request) {
        if (taskId == null) {
            outcome = withContext(Dispatchers.IO) { runCatching { ToolDrafts.gson.fromJson(context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE)
                .getString(ToolDrafts.key(request) + ":outcome", null), FlowOutcome::class.java) }.getOrNull() }
            if (stage == 1) { stage = 0; saveStatus = "旧版中断任务没有逐项回执，配置已恢复，请重新提交。" }
        }
    }
    LaunchedEffect(task?.updatedAt, taskId) {
        val current = task ?: return@LaunchedEffect
        if (current.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)) stage = 1
        else if (current.state in setOf(ToolTaskState.SUCCEEDED, ToolTaskState.PARTIAL)) {
            outcome = withContext(Dispatchers.IO) { ToolTasks.outcome(context, current.id) }
            stage = 2
        } else if (stage == 1) stage = 0
    }
    fun resumeTask() {
        val id = taskId ?: return
        val secret = runCatching { taskPassword() }.getOrElse { vm.notify(it.message ?: "请检查密码"); return }
        scope.launch {
            try { ToolTasks.resume(context, id, secret); stage = 1 }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify(e.message ?: "续跑失败") }
            finally { secret?.fill('\u0000') }
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

    fun saveToPhone(files: List<File>, useDefault: Boolean = true) {
        if (files.isEmpty() || busy) return
        if(useDefault && com.localdoc.scanner.data.AppPreferences(context).text("destination").isNotBlank()) {
            busy = true
            scope.launch { try {
                val labels = withContext(Dispatchers.IO) { files.map { com.localdoc.scanner.output.DefaultDestination.save(context, it) } }
                saveStatus = "已保存到默认文件夹：\n" + labels.joinToString("\n")
            } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; saveStatus = "保存失败：${e.message}；可另选位置" }
            finally { busy=false } }
            return
        }
        pendingSaveFiles = files
        if (files.size == 1) saveSingle.launch(files.first().name) else saveMany.launch(null)
    }

    fun start() {
        if (busy || task?.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)) return
        val parameters: Map<String, String>
        val secret: CharArray?
        try { parameters = ToolDrafts.snapshot(context, request) + extraParameters(); secret = taskPassword() }
        catch (e: Exception) { vm.notify(e.message ?: "请检查配置"); return }
        busy = true
        scope.launch {
            try {
                taskId = ToolTasks.submit(context, request, parameters, password = secret)
                outcome = null; saveStatus = ""; showInputAfterProcessing = false; stage = 1
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify(e.message ?: "任务提交失败") }
            finally { busy = false; secret?.fill('\u0000') }
        }
    }

    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    Scaffold(
        modifier = modifier,
        topBar = {
            ScannerTopBar(request.tool.label, onBack, "配置 · 处理 · 保存")
        },
        bottomBar = {
            ActionDock {
                when (stage) {
                    0 -> {
                        Button(onClick = { start() }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text(runLabel) }
                        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
                    }
                    1 -> {
                        TextButton(onClick = { taskId?.let { scope.launch { ToolTasks.pause(context, it) } } }) { Text("暂停并保留进度") }
                        TextButton(onClick = onBack) { Text("返回，后台继续") }
                    }
                    else -> {
                        val o = outcome
                        if(com.localdoc.scanner.data.AppPreferences(context).text("destination").isNotBlank()) TextButton(onClick={ saveToPhone(o?.files.orEmpty(), false) },enabled=!busy) { Text("另选位置") }
                        Button(onClick = { saveToPhone(o?.files.orEmpty()) }, enabled = !busy && !o?.files.isNullOrEmpty(), modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                            Text(if (busy) "保存中…" else "保存到手机")
                        }
                        FilledTonalIconButton(
                            onClick = {
                                if (o != null && o.files.isNotEmpty()) {
                                    Share.files(context, o.files, guessMime(o.files.first()))
                                } else {
                                    toast("没有可分享的文件")
                                }
                            },
                            enabled = !busy && !o?.files.isNullOrEmpty()
                        ) { AppIcon(R.drawable.ic_ui_share, "分享结果") }
                        OutlinedIconButton(
                            onClick = {
                                val file = o?.files?.singleOrNull()
                                if (file == null || !Share.open(context, file, guessMime(file))) toast("当前结果无法打开")
                            },
                            enabled = o?.files?.size == 1
                        ) { AppIcon(R.drawable.ic_ui_folder, "打开结果文件") }
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
                InfoCard("已选 ${request.files.size} 个文件", request.names.take(2).joinToString("\n") + if (request.names.size > 2) "\n另 ${request.names.size - 2} 个文件" else "", R.drawable.ic_ui_folder)
                if (showInputPreview) {
                    TextButton(onClick = { expandedInputPreview = !expandedInputPreview }) { Text(if (expandedInputPreview) "收起输入预览" else "查看输入预览") }
                    if (expandedInputPreview) FilePreview(request.files, "输入预览")
                }
                if (saveStatus.isNotBlank()) Text(saveStatus)
                task?.let { ToolTaskPanel(it, onPause = { scope.launch { ToolTasks.pause(context, it.id) } }, onResume = ::resumeTask) }
                if (task?.state in setOf(ToolTaskState.PAUSED, ToolTaskState.FAILED, ToolTaskState.WAITING_PASSWORD)) {
                    TextButton(onClick = { scope.launch {
                        try { outcome = withContext(Dispatchers.IO) { ToolTasks.recoveredOutcome(context, task!!.id) }; stage = 2 }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("恢复产物失败：${e.message}") }
                    } }) { Text("查看 / 保存已完成步骤的产物") }
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { config() }
                }
            }

            1 -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard("正在处理文件", "可以返回首页，稍后到工具任务查看进度。", R.drawable.ic_ui_play)
                task?.let { ToolTaskPanel(it, onPause = { scope.launch { ToolTasks.pause(context, it.id) } }, onResume = ::resumeTask) }
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
                task?.let { ToolTaskPanel(it, onPause = {}, onResume = ::resumeTask) }
                if (task == null) Text(when {
                    task?.state == ToolTaskState.PARTIAL -> "部分完成"
                    task?.state in setOf(ToolTaskState.PAUSED, ToolTaskState.FAILED, ToolTaskState.WAITING_PASSWORD) -> "已完成步骤的产物"
                    o == null || o.summary.any { it.first == "出错" } -> "处理失败"
                    o.files.isEmpty() -> "处理结束，无输出文件"
                    else -> "处理完成"
                }, style = MaterialTheme.typography.titleMedium)
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
                        SectionHeading("输出文件")
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
