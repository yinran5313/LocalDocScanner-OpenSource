package com.localdoc.scanner.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.data.AppPreferences
import com.localdoc.scanner.data.rememberPreference
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.ui.components.WrappingOptions

@Composable
internal fun DefaultSettings() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val name by rememberPreference("name", "拾页_{date}_{time}")
    val paper by rememberPreference("paper", "A4")
    val quality by rememberPreference("quality", "高清")
    val theme by rememberPreference("theme", "system")
    val destination by rememberPreference("destination")
    var status by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "文件夹不可写入" }
            prefs.set("destination", uri.toString()); status = "默认文件夹已保存"
        }.onFailure { status = "授权失败：${it.message}" }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("常用默认设置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { prefs.set("name", it.take(100)) }, label = { Text("扫描文档命名") }, supportingText = { Text("{date} 日期 · {time} 时间；新文档使用此规则") }, modifier = Modifier.fillMaxWidth())
        Text("纸张")
        WrappingOptions { PdfExporter.PageSize.entries.forEach { size ->
            FilterChip(paper == size.name, { prefs.set("paper", size.name) }, label = { Text(when(size.name) { "FIT_IMAGE" -> "适合图片"; else -> size.name }) })
        } }
        WrappingOptions { listOf("高清", "标准").forEach { item -> FilterChip(quality == item, { prefs.set("quality", item) }, label = { Text(item) }) } }
        Text("外观")
        WrappingOptions { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
            FilterChip(theme == key, { prefs.set("theme", key) }, label = { Text(label) })
        } }
        Text(if (destination.isBlank()) "保存时询问位置" else "默认文件夹：${android.net.Uri.parse(destination).lastPathSegment}", style = MaterialTheme.typography.bodySmall)
        WrappingOptions { TextButton(onClick = { picker.launch(null) }) { Text("选择保存文件夹") }; if (destination.isNotBlank()) TextButton(onClick = { prefs.set("destination", "") }) { Text("清除默认") } }
        Text("导出结果可点“保存到默认文件夹”，也可每次另选位置；不会覆盖同名文件。", style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status)
    } }
}
