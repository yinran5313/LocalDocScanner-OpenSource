package com.localdoc.scanner.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<StorageFile>>(emptyList()) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    fun refresh() { scope.launch {
        busy = true
        try { files = withContext(Dispatchers.IO) { StorageInventory.inspect(context) } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() }
        finally { busy = false }
    } }
    LaunchedEffect(Unit) { refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("内部文件与空间") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        actions = { TextButton(onClick = ::refresh, enabled = !busy) { Text("刷新") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("统计扫描、导出、导入和任务工作文件。模型、Office运行时和数据库不在清理范围内。引用中的文件、最近24小时文件、未完成任务、损坏草稿及恢复副本会保留。") }
            files.groupBy { it.category }.forEach { (category, group) -> item { Text("$category · ${group.size}个 · ${"%.1f".format(group.sumOf { it.bytes } / 1048576.0)} MB") } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (status.isNotBlank()) item { Text(status) }
            item { Text("待核对的未引用文件", style = MaterialTheme.typography.titleMedium) }
            items(files.filter { !it.referenced }, key = { it.path }) { file ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(file.path in selection, { selected -> selection = if (selected) selection + file.path else selection - file.path }, enabled = !busy)
                    Column(Modifier.weight(1f)) {
                        Text(File(file.path).name); Text("${file.category} · ${"%.1f".format(file.bytes / 1024.0)} KB", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { com.localdoc.scanner.util.Share.open(context, File(file.path), com.localdoc.scanner.output.OutputHistoryStore.mimeFor(File(file.path))) }) { Text("先打开核对") }
                    }
                }
            }
            item { Button(onClick = { confirm = true }, enabled = !busy && selection.isNotEmpty()) { Text("删除选中的 ${selection.size} 个内部文件") } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("删除所选内部文件？") },
        text = { Text("仅删除明确选中的未引用文件；不会删除手机上已保存的文件。完成任务中间文件被清理后，再处理可能需要重新计算。") },
        confirmButton = { Button(onClick = {
            val selected = selection.toSet(); confirm = false; busy = true
            scope.launch { try {
                val count = withContext(Dispatchers.IO) { StorageInventory.removeSelected(context, selected) }
                selection = emptySet(); status = "已删除$count 个文件；重新被引用的文件已保留。"
                files = withContext(Dispatchers.IO) { StorageInventory.inspect(context) }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = "清理失败：${e.message}" }
            finally { busy = false } }
        }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
