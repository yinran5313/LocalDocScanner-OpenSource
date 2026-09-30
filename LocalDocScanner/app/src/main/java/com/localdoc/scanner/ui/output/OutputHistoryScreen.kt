package com.localdoc.scanner.ui.output

import android.net.Uri
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
    var records by remember { mutableStateOf(OutputHistoryStore.all(context)) }
    var officeEngine by remember { mutableStateOf(OfficeEngineBridge.installed(context)) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("导出记录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = {
                    records = OutputHistoryStore.all(context)
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
                            } else if (record.savedUri.isNotBlank()) {
                                Share.openUri(context, Uri.parse(record.savedUri), record.mime)
                            } else internal.takeIf { it.isFile }?.let { Share.open(context, it, record.mime) } == true
                            if (!opened) records = OutputHistoryStore.all(context)
                        }) { Text(when {
                            isFolder -> "打开位置"
                            officeFormat != null && officeEngine == null -> "安装完整引擎"
                            officeFormat != null -> "完整编辑"
                            else -> "打开"
                        }) }
                        if (!isFolder) {
                            TextButton(onClick = {
                                if (internal.isFile) Share.file(context, internal, record.mime)
                                else if (record.savedUri.isNotBlank()) Share.uri(context, Uri.parse(record.savedUri), record.mime)
                            }) { Text("分享") }
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
