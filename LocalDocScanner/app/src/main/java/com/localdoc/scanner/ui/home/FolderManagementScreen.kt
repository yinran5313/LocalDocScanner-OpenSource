package com.localdoc.scanner.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.data.LibraryFolders
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.components.ScannerTopBar
import kotlinx.coroutines.launch

@Composable
fun FolderManagementScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val docs by vm.docs.collectAsState()
    var names by remember { mutableStateOf(LibraryFolders.all(context)) }; var selected by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    fun apply(remove: Boolean) {
        val folder = selected ?: return
        busy = true
        scope.launch {
            try { vm.changeFolder(folder, if (remove) null else value); names = LibraryFolders.all(context); selected = null; delete = false; status = if (remove) "文件夹已移除，文档保留在全部文档中" else "文件夹已改名" }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { ScannerTopBar("文件夹管理",onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("改名会更新文档归属；移除文件夹会把文档归还到全部文档，不删除文件。证件卡包保留为专用分类。") }
            item { Text(status) }
            items((names+docs.mapNotNull { it.folder }).distinct().sorted()) { folder ->
                ListItem(headlineContent = { Text(folder) }, supportingContent = { Text("${docs.count { it.folder == folder }}份文档") }, trailingContent = {
                    TextButton({ selected = folder; value = folder; delete = false },enabled = !busy && folder != "证件卡包") { Text("管理") }
                })
            }
        }
    }
    selected?.let { folder -> AlertDialog(onDismissRequest = { if (!busy) selected = null },title = { Text(if (delete) "移除文件夹？" else "管理 $folder") }, text = {
        if (delete) Text("其中的文档和回收站记录都会保留；文件夹归属改为未分类。") else OutlinedTextField(value,{ value = it.take(80) },label = { Text("新的名称") })
    },confirmButton = { TextButton({ apply(delete) },enabled = !busy && (delete || value.isNotBlank())) { Text(if (busy) "保存中…" else if (delete) "移除文件夹" else "保存改名") } },dismissButton = {
        Row { if (!delete) TextButton({ delete = true },enabled = !busy) { Text("移除") }; TextButton({ selected = null },enabled = !busy) { Text("取消") } }
    }) }
}
