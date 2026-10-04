package com.localdoc.scanner.ui.output

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.office.OfficeEngineBridge
import com.localdoc.scanner.office.OfficeFormats
import com.localdoc.scanner.util.Share
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputHistoryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingJson by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<com.localdoc.scanner.output.OutputRecord>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { records = withContext(Dispatchers.IO) { OutputHistoryStore.all(context) } }
    var officeEngine by remember { mutableStateOf(OfficeEngineBridge.installed(context)) }
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val record = runCatching { com.localdoc.scanner.data.ToolDrafts.gson.fromJson(pendingJson, com.localdoc.scanner.output.OutputRecord::class.java) }.getOrNull()
        if (record != null) scope.launch {
            busy = true
            val failed = result.data?.getBooleanExtra("localdoc_save_failed", false) == true
            val recovery = result.data?.getStringExtra("localdoc_recovery_path")?.let(::File)?.takeIf { it.isFile }
            val file = recovery ?: File(record.internalPath)
            status = withContext(Dispatchers.IO) {
                runCatching {
                    require(file.isFile) { "工作副本无法读取" }
                    OutputHistoryStore.recordGenerated(context, file, record.mime)
                    if (failed) return@runCatching "保存失败，修改的恢复副本已登记，请另存。"
                    if (record.savedUri.isNotBlank()) {
                        val uri = Uri.parse(record.savedUri)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: error("已更新工作副本，但无法写回保存位置，请另存")
                        OutputHistoryStore.markSaved(context, file, uri, record.savedLabel)
                        "修改已同步到保存位置"
                    } else "工作副本已更新，可以分享或另存到手机"
                }.getOrElse { "同步失败：${it.message}；内部副本保留在导出记录。" }
            }
            records = withContext(Dispatchers.IO) { OutputHistoryStore.all(context) }
            busy = false
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("导出记录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = {
                    scope.launch { records = withContext(Dispatchers.IO) { OutputHistoryStore.all(context) } }
                    officeEngine = OfficeEngineBridge.installed(context)
                }) { Text("刷新") } }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("还没有导出结果", style = MaterialTheme.typography.titleMedium)
                Text("工具生成的文件会显示在这里，并标明是否已经保存到手机。")
            }
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (status.isNotBlank()) item { Text(status) }
            items(records, key = { it.id }) { record ->
                val internal = File(record.internalPath)
                val isFolder = record.mime == DocumentsContract.Document.MIME_TYPE_DIR
                val officeFormat = OfficeFormats.detect(record.name, record.mime)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.name, style = MaterialTheme.typography.titleSmall)
                        Text("${formatSize(record.size)} · ${formatTime(record.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (record.savedUri.isNotBlank()) "已保存：${record.savedLabel}" else "应用内部结果，尚未保存到手机",
                            color = if (record.savedUri.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            if (officeFormat != null && officeEngine != null) {
                                scope.launch {
                                    busy = true
                                    val ready = withContext(Dispatchers.IO) { runCatching {
                                        val work = if (internal.isFile) internal else File(context.filesDir, "office-work/${record.id}/${record.name}")
                                        work.parentFile?.mkdirs()
                                        if (record.savedUri.isNotBlank()) {
                                            val part = File(work.parentFile, work.name + ".refresh")
                                            try {
                                                context.contentResolver.openInputStream(Uri.parse(record.savedUri))?.use { input -> part.outputStream().use { input.copyTo(it) } }
                                                    ?: error("无法读取保存位置；请选择内部副本或重新授权")
                                                require(part.length() > 0 && part.renameTo(work)) { "无法更新工作副本" }
                                            } finally { part.delete() }
                                            OutputHistoryStore.markSaved(context, work, Uri.parse(record.savedUri), record.savedLabel)
                                        }
                                        require(work.isFile) { "文件已不存在" }
                                        work
                                    } }
                                    ready.onSuccess { file ->
                                        pendingJson = com.localdoc.scanner.data.ToolDrafts.gson.toJson(record.copy(internalPath = file.absolutePath))
                                        editor.launch(OfficeEngineBridge.editIntent(context, file, officeFormat.mime, officeEngine!!))
                                    }.onFailure { status = "打开失败：${it.message}" }
                                    busy = false
                                }
                                return@Button
                            }
                            val opened = if (officeFormat != null) {
                                val engine = officeEngine
                                when {
                                    engine == null -> OfficeEngineBridge.openInstallPage(context)
                                    record.savedUri.isNotBlank() -> OfficeEngineBridge.openEditor(
                                        context, Uri.parse(record.savedUri), officeFormat.mime, engine
                                    )
                                    internal.isFile -> OfficeEngineBridge.openEditor(context, internal, officeFormat.mime, engine)
                                    else -> false
                                }
                            } else if (!isFolder && (internal.isFile || record.savedUri.isNotBlank())) {
                                val uri = if (record.savedUri.isNotBlank()) Uri.parse(record.savedUri)
                                    else androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", internal)
                                com.localdoc.scanner.external.ExternalOpenBus.offer(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, record.mime) }, context.contentResolver)
                                true
                            } else if (record.savedUri.isNotBlank()) {
                                Share.openUri(context, Uri.parse(record.savedUri), record.mime)
                            } else internal.takeIf { it.isFile }?.let { Share.open(context, it, record.mime) } == true
                            if (!opened) scope.launch { records = withContext(Dispatchers.IO) { OutputHistoryStore.all(context) } }
                        }, enabled = !busy) { Text(when {
                            isFolder -> "打开位置"
                            officeFormat != null && officeEngine == null -> "安装完整引擎"
                            officeFormat != null -> "完整编辑"
                            else -> "打开"
                        }) }
                        if (!isFolder) {
                            TextButton(onClick = {
                                if (record.savedUri.isNotBlank()) Share.uri(context, Uri.parse(record.savedUri), record.mime)
                                else if (internal.isFile) Share.file(context, internal, record.mime)
                            }, enabled = !busy) { Text("分享") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
