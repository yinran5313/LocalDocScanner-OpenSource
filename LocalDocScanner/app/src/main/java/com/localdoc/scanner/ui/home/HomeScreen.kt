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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.localdoc.scanner.R
import com.localdoc.scanner.ui.components.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.model.ToolEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    docs: List<DocItem>,
    draftCount: Int,
    draftProblem: String,
    onBackupDraft: () -> Unit,
    onCaptureClick: () -> Unit,
    onImportClick: () -> Unit,
    onOpenFileClick: () -> Unit,
    onResumeDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onTrashClick: () -> Unit,
    onOutputHistoryClick: () -> Unit,
    onLibraryWorkbench: () -> Unit,
    onToolTasks: () -> Unit,
    onStorage: () -> Unit,
    onSettingsClick: () -> Unit,
    onToolClick: (ToolEntry) -> Unit,
    onDocClick: (DocItem) -> Unit,
    onRenameDoc: (DocItem, String) -> Unit,
    onOrganizeDoc: (DocItem, String?, String) -> Unit,
    onTrashDoc: (DocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val compactActions = configuration.screenWidthDp < 360 || configuration.fontScale > 1.15f
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
    var showAllDocs by rememberSaveable { mutableStateOf(false) }

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
                title = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("本地扫描", style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        AppIcon(R.drawable.ic_tool_lock, modifier = Modifier.size(13.dp))
                        Text("文件留在本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = onOutputHistoryClick) { AppIcon(R.drawable.ic_ui_history, "导出记录") }
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) { AppIcon(R.drawable.ic_ui_more, "更多操作") }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("回收站") }, onClick = { topMenuOpen = false; onTrashClick() })
                            DropdownMenuItem(text = { Text("内部文件与空间") }, onClick = { topMenuOpen = false; onStorage() })
                            DropdownMenuItem(text = { Text("设置") }, onClick = { topMenuOpen = false; onSettingsClick() })
                        }
                    }
                }
            )
        },
        bottomBar = { ActionDock {
            Button(onClick = if (draftCount > 0) onResumeDraft else onCaptureClick,
                modifier = Modifier.weight(1.35f).heightIn(min = 54.dp), shape = MaterialTheme.shapes.medium) {
                if (!compactActions) { AppIcon(R.drawable.ic_ui_camera); Spacer(Modifier.width(8.dp)) }
                Text(if (draftCount > 0) "继续扫描" else "拍照扫描", maxLines = 1)
            }
            OutlinedButton(onClick = { importMenuOpen = true }, modifier = Modifier.weight(1f).heightIn(min = 54.dp), shape = MaterialTheme.shapes.medium) {
                if (!compactActions) { AppIcon(R.drawable.ic_ui_folder); Spacer(Modifier.width(6.dp)) }; Text(if (compactActions) "导入" else "导入 / 打开", maxLines = 1)
            }
        } }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索文档或识别文字", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { AppIcon(R.drawable.ic_ui_search) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { AppIcon(R.drawable.ic_ui_close, "清除搜索") } },
                    singleLine = true, shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface))
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeQuickAction("工具任务", "查看进度 · 继续处理", R.drawable.ic_ui_play, onToolTasks, Modifier.weight(1f))
                HomeQuickAction("文档整理", "全文搜索 · 批量归档", R.drawable.ic_ui_folder, onLibraryWorkbench, Modifier.weight(1f))
            } }
            if (draftProblem.isNotBlank()) item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("草稿需要处理", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(draftProblem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row { TextButton(onClick = onBackupDraft) { Text("备份原始文件") }; TextButton(onClick = { confirmDiscard = true }) { Text("放弃草稿") } }
                    }
                }
            }
            if (draftCount > 0) item {
                Surface(onClick = onResumeDraft, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("还有 $draftCount 页待整理", style = MaterialTheme.typography.titleSmall); Text("草稿已保留，点此继续", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { confirmDiscard = true }) { Text("放弃") }
                        AppIcon(R.drawable.ic_chevron_right)
                    }
                }
            }
            item { SectionHeading(if (query.isBlank()) "最近文档" else "搜索结果") {
                if (query.isBlank() && visibleDocs.size > 3) TextButton(onClick = { showAllDocs = !showAllDocs }) { Text(if (showAllDocs) "收起" else "查看全部 · ${visibleDocs.size}") }
                else Text("${visibleDocs.size} 份", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (visibleDocs.isEmpty()) item {
                InfoCard(if (query.isBlank()) "你的文件，从这里开始" else "没有找到匹配文档",
                    if (query.isBlank()) "扫描纸张，或打开已有的图片、PDF和Office文件。" else "试试名称、文件夹、标签或识别文字中的关键词。", R.drawable.ic_ui_folder)
            }
            items(if (showAllDocs || query.isNotBlank()) visibleDocs else visibleDocs.take(3), key = { it.id }) { doc ->
                RecentDocumentRow(doc, onClick = { onDocClick(doc) }, onMenu = { menuDoc = doc })
            }
            item { SectionHeading("文档工具") { Text("按用途分组", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { ToolGrid(onToolClick = onToolClick) }
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

@Composable
private fun HomeQuickAction(title: String, subtitle: String, icon: Int, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIcon(icon)
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
