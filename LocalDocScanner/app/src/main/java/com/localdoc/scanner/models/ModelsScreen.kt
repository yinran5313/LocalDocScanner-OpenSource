package com.localdoc.scanner.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.localdoc.scanner.ui.components.ScannerTopBar
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packs = remember { ModelCatalog.all(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var wifiOnly by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ModelPack?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "正在校验和导入模型，请保持应用打开…"
            try { val pack = ModelDownloads.import(context, uri); status = "${pack.title}已安装，后续处理可离线运行"; revision++ }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message ?: "模型导入失败" }
        }
    }
    Scaffold(modifier, topBar = { ScannerTopBar("模型管理", onBack, "按用途选择，下载后在本机处理") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("高精度 OCR · medium 已内置", style = MaterialTheme.typography.titleMedium); Text("扫描和文字识别直接可用。其他模型按需下载，也可导入离线模型包。") }
            item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(wifiOnly, { wifiOnly = it }); Text("仅使用 Wi-Fi 下载") }; Text("关闭此项后可使用移动数据，下载大小见各模型。", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入离线模型包") }; if (status.isNotBlank()) Text(status) }
            item {
                Text("后续可选包",style = MaterialTheme.typography.titleSmall)
                Text("更多翻译方向仍在适配，不提供下载按钮。降噪无需额外模型；去模糊和图文版面见下方已可用模型。",style = MaterialTheme.typography.bodySmall)
            }
            packs.forEach { pack -> item(key = pack.id) {
                val works by remember(pack.id) { ModelDownloads.observe(context, pack.id) }.collectAsState(emptyList())
                val work = works.firstOrNull()
                val installed = remember(revision, works, pack.id) { ModelCatalog.installed(context, pack) }
                val running = work != null && !work!!.state.isFinished
                val done = work?.progress?.getLong("done", 0) ?: 0L
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(pack.title, style = MaterialTheme.typography.titleMedium)
                        Text(pack.description)
                        Text("${"%.1f".format(pack.bytes / 1048576.0)} MiB · ${if (installed) "已安装" else "未安装"}", style = MaterialTheme.typography.bodySmall)
                        Text("版本 ${pack.version} · ${pack.license}", style = MaterialTheme.typography.labelSmall)
                        if (running) {
                            LinearProgressIndicator(progress = { (done.toFloat() / pack.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(if (work?.state == WorkInfo.State.ENQUEUED) "等待网络或继续下载；已完成 ${done / 1048576} MiB" else "正在下载 ${done / 1048576}/${pack.bytes / 1048576} MiB")
                            TextButton(onClick = { ModelDownloads.pause(context, pack.id) }) { Text("暂停，保留进度") }
                        } else if (!installed) {
                            if (pack.files.all { it.urls.isNotEmpty() }) com.localdoc.scanner.ui.components.WrappingOptions {
                                Button(onClick = { ModelDownloads.start(context, pack.id, wifiOnly) }) { Text("下载 / 继续") }
                                TextButton(onClick = { ModelDownloads.start(context, pack.id, wifiOnly, 1) }, enabled = pack.files.all { it.urls.size > 1 }) { Text("备用下载源") }
                            }
                            if (pack.files.any { it.urls.isEmpty() }) Text("请导入离线模型包。直接下载入口待可用下载源确认后提供。", style = MaterialTheme.typography.bodySmall)
                            work?.outputData?.getString("error")?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        } else TextButton(onClick = { deleting = pack }) { Text("删除模型，释放空间") }
                    }
                }
            } }
        }
    }
    deleting?.let { pack -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${pack.title}？") },
        text = { Text("原文档不会删除；再次使用此功能时需要重新下载或导入模型。") },
        confirmButton = { TextButton(onClick = { deleting = null; scope.launch { try { ModelDownloads.remove(context, pack); revision++; status = "模型已删除" } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() } } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
