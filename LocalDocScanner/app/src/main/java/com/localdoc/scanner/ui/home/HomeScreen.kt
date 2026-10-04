package com.localdoc.scanner.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.model.ToolEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    docs: List<DocItem>,
    draftCount: Int,
    onCaptureClick: () -> Unit,
    onImportClick: () -> Unit,
    onOpenFileClick: () -> Unit,
    onResumeDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onTrashClick: () -> Unit,
    onOutputHistoryClick: () -> Unit,
    onLibraryWorkbench: () -> Unit,
    onSettingsClick: () -> Unit,
    onToolClick: (ToolEntry) -> Unit,
    onDocClick: (DocItem) -> Unit,
    onRenameDoc: (DocItem, String) -> Unit,
    onOrganizeDoc: (DocItem, String?, String) -> Unit,
    onTrashDoc: (DocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menuDoc by remember { mutableStateOf<DocItem?>(null) }
    var renameDoc by remember { mutableStateOf<DocItem?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var organizeDoc by remember { mutableStateOf<DocItem?>(null) }
    var folderValue by remember { mutableStateOf("") }
    var tagsValue by remember { mutableStateOf("") }
    var confirmTrashDoc by remember { mutableStateOf<DocItem?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var importMenuOpen by remember { mutableStateOf(false) }

    val visibleDocs = remember(docs, query) {
        if (query.isBlank()) docs else docs.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.folder.orEmpty().contains(query, ignoreCase = true) ||
                it.tags.contains(query, ignoreCase = true) ||
                it.ocrText.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("本地扫描")
                        Text("文件只保存在你的手机", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = onOutputHistoryClick) { Text("导出记录") }
                    Box {
                        TextButton(onClick = { topMenuOpen = true }) { Text("更多") }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("回收站") }, onClick = { topMenuOpen = false; onTrashClick() })
                            DropdownMenuItem(text = { Text("设置") }, onClick = { topMenuOpen = false; onSettingsClick() })
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 5.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = if (draftCount > 0) onResumeDraft else onCaptureClick,
                        modifier = Modifier.weight(1.25f)
                    ) {
                        Text(if (draftCount > 0) "继续扫描 · $draftCount 页" else "扫描")
                    }
                    OutlinedButton(onClick = { importMenuOpen = true }, modifier = Modifier.weight(1f)) { Text("导入/打开") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索名称、文件夹、标签或识别文字") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )
                TextButton(onClick = onLibraryWorkbench) { Text("全文检索 / 批量归档") }
            }
            if (draftCount > 0) {
                item {
                    Surface(
                        onClick = onResumeDraft,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("未完成的扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("已保留 $draftCount 页，退出应用后仍可继续", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onResumeDraft, modifier = Modifier.weight(1f)) { Text("继续整理") }
                                TextButton(onClick = { confirmDiscard = true }) { Text("放弃草稿") }
                            }
                        }
                    }
                }
            }
            item { Text("文档工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { ToolGrid(onToolClick = onToolClick) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("最近文档", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${visibleDocs.size} 份", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visibleDocs.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "还没有文档，用下方按钮开始扫描或导入" else "没有找到匹配的文档",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(visibleDocs.chunked(2)) { row ->
                    DocRow(items = row, onDocClick = onDocClick, onDocLongClick = { menuDoc = it })
                }
            }
        }
    }

    menuDoc?.let { doc ->
        ModalBottomSheet(onDismissRequest = { menuDoc = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    doc.title,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${doc.pageCount} 页",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ListItem(
                    headlineContent = { Text("打开、分享或编辑") },
                    modifier = Modifier.clickable { menuDoc = null; onDocClick(doc) }
                )
                ListItem(
                    headlineContent = { Text("重命名") },
                    modifier = Modifier.clickable {
                        menuDoc = null
                        renameDoc = doc
                        renameValue = doc.title
                    }
                )
                ListItem(
                    headlineContent = { Text("文件夹和标签") },
                    supportingContent = { Text("整理文档，也可用于首页搜索") },
                    modifier = Modifier.clickable {
                        menuDoc = null
                        organizeDoc = doc
                        folderValue = doc.folder.orEmpty()
                        tagsValue = doc.tags
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("移到回收站", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { menuDoc = null; confirmTrashDoc = doc }
                )
            }
        }
    }

    if (importMenuOpen) {
        ModalBottomSheet(onDismissRequest = { importMenuOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    "导入或打开",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ListItem(
                    headlineContent = { Text("导入图片进行扫描编辑") },
                    supportingContent = { Text("裁边、增强并加入扫描文档") },
                    modifier = Modifier.clickable { importMenuOpen = false; onImportClick() }
                )
                ListItem(
                    headlineContent = { Text("打开Office、PDF或文本") },
                    supportingContent = { Text("Word、Excel、PPT会进入完整Office工作区") },
                    modifier = Modifier.clickable { importMenuOpen = false; onOpenFileClick() }
                )
            }
        }
    }

    renameDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { renameDoc = null },
            title = { Text("重命名文档") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文档名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = { onRenameDoc(doc, renameValue.trim()); renameDoc = null }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameDoc = null }) { Text("取消") } }
        )
    }

    organizeDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { organizeDoc = null },
            title = { Text("整理文档") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = folderValue,
                        onValueChange = { folderValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件夹（可留空）") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tagsValue,
                        onValueChange = { tagsValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标签，用逗号分隔") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onOrganizeDoc(doc, folderValue.trim().ifBlank { null }, tagsValue)
                    organizeDoc = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { organizeDoc = null }) { Text("取消") } }
        )
    }

    confirmTrashDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { confirmTrashDoc = null },
            title = { Text("移到回收站？") },
            text = { Text("“${doc.title}”可以稍后从回收站恢复。") },
            confirmButton = {
                TextButton(onClick = { onTrashDoc(doc); confirmTrashDoc = null }) {
                    Text("移到回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmTrashDoc = null }) { Text("取消") } }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这份草稿？") },
            text = { Text("草稿中的原图和编辑结果会被删除，已保存的文档不受影响。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscardDraft() }) { Text("放弃草稿") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续保留") } }
        )
    }
}
