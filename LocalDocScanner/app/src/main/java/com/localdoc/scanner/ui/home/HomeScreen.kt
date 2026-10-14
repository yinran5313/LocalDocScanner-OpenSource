package com.localdoc.scanner.ui.home

import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    modifier: Modifier = Modifier,
    onFavoriteDoc: (DocItem) -> Unit = {},
    onModelsClick: () -> Unit = {},
    onNewDocument: () -> Unit = {},
    onManageFolders: () -> Unit = {},
    onSourceInfo: (DocItem) -> Unit = {},
    onBatchDocuments: (suspend (List<String>, String, String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { com.localdoc.scanner.data.AppPreferences(context) }
    val sort by com.localdoc.scanner.data.rememberPreference("sort", "updated")
    val layout by com.localdoc.scanner.data.rememberPreference("layout", "list")
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var libraryOptions by remember { mutableStateOf(false) }
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var batchSaving by remember { mutableStateOf(false) }
    var batchValue by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(com.localdoc.scanner.data.LibraryFolders.all(context)) }
    var folderError by remember { mutableStateOf("") }

    val visibleDocs = remember(docs, query, sort, favoritesOnly, folderFilter) {
        val matched = if (query.isBlank()) docs else docs.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.folder.orEmpty().contains(query, ignoreCase = true) ||
                it.tags.contains(query, ignoreCase = true) ||
                it.ocrText.contains(query, ignoreCase = true)
        }
        val filtered = (if (favoritesOnly) matched.filter { it.favorite } else matched).filter { folderFilter == null || it.folder.orEmpty() == folderFilter }
        when (sort) { "name" -> filtered.sortedBy { it.title.lowercase() }; "created" -> filtered.sortedByDescending { it.createdAt }; "size" -> filtered.sortedByDescending { it.sizeBytes }; else -> filtered.sortedByDescending { it.updatedAt } }
    }

    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.brand_mark), null, Modifier.size(34.dp))
            Text(listOf("拾页", "全部文档", "工具箱", "我的")[tab], style = MaterialTheme.typography.titleLarge)
        } }, actions = {
            IconButton(onOutputHistoryClick) { AppIcon(R.drawable.ic_ui_history, "导出记录") }
            IconButton(onSettingsClick) { AppIcon(R.drawable.ic_ui_more, "设置") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary))
    }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val entries = listOf("首页" to R.drawable.ic_tool_document_scanner, "文档" to R.drawable.ic_ui_folder,
                    "扫描" to R.drawable.ic_ui_camera, "工具" to R.drawable.ic_tool_tune, "我的" to R.drawable.ic_tool_badge)
                entries.forEachIndexed { index, (label, icon) ->
                    val target = if (index < 2) index else index - 1
                    Column(Modifier.weight(1f).clickable {
                        if (index == 2) { if (draftCount > 0) onResumeDraft() else onCaptureClick() }
                        else tab = target
                    }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(15.dp), color = when {
                            index == 2 -> MaterialTheme.colorScheme.primary
                            tab == target -> MaterialTheme.colorScheme.primaryContainer
                            else -> androidx.compose.ui.graphics.Color.Transparent
                        }) { Icon(painterResource(icon), label, Modifier.size(38.dp).padding(7.dp),
                            tint = if (index == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (index != 2 && tab == target) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0 || tab == 1) item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索文件、标签或识别文字", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { AppIcon(R.drawable.ic_ui_search) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            }
            if (tab == 0) item {
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("常用工具", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall); TextButton({ tab = 2 }) { Text("全部工具") } }
                        CompactToolRows(com.localdoc.scanner.model.TOOL_ENTRIES.filter { it.id in listOf("image_edit", "images_to_pdf", "pdf_office", "ocr", "card", "batch_extract") } +
                            listOf(ToolEntry("home_import", "导入文件", com.localdoc.scanner.model.FileKind.NONE), ToolEntry("home_new", "新建文档", com.localdoc.scanner.model.FileKind.NONE))) { tool ->
                            when(tool.id) { "home_import" -> importMenuOpen = true; "home_new" -> onNewDocument(); else -> onToolClick(tool) }
                        }
                    }
                }
            }
            if ((tab == 0 || tab == 1) && draftProblem.isNotBlank()) item {
                InfoCard("草稿需要处理", draftProblem, R.drawable.ic_ui_history)
                Row { TextButton(onBackupDraft) { Text("备份原文件") }; TextButton({ confirmDiscard = true }) { Text("放弃草稿") } }
            }
            if ((tab == 0 || tab == 1) && draftCount > 0) item {
                ListItem(headlineContent = { Text("继续扫描 · $draftCount 页") }, supportingContent = { Text("草稿已经保留") },
                    trailingContent = { TextButton({ confirmDiscard = true }) { Text("放弃") } }, modifier = Modifier.clickable(onClick = onResumeDraft))
            }
            if (tab == 0 || tab == 1) {
                item { SectionHeading(if (query.isNotBlank()) "搜索结果" else if (tab == 0) "最近文档" else "全部文档") {
                    TextButton(if (tab == 0) ({ tab = 1 }) else onLibraryWorkbench) { Text(if (tab == 0) "查看全部" else "批量整理") }
                } }
                if (tab == 1) item {
                    WrappingOptions {
                        FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("收藏") })
                        TextButton(onNewDocument) { Text("新建") }
                        TextButton({ importMenuOpen = true }) { Text("导入") }
                        TextButton({ batchValue = ""; newFolder = true }) { Text("新文件夹") }
                        TextButton(onManageFolders) { Text("管理文件夹") }
                        Box {
                            IconButton({ libraryOptions = true }) { AppIcon(R.drawable.ic_tool_tune, "排序和布局") }
                            DropdownMenu(libraryOptions, { libraryOptions = false }) {
                                listOf("updated" to "最近修改", "created" to "创建时间", "name" to "名称", "size" to "大小").forEach { (key, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = { preferences.set("sort", key); libraryOptions = false })
                                }
                                HorizontalDivider()
                                listOf("list" to "列表", "grid" to "网格").forEach { (key, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = { preferences.set("layout", key); libraryOptions = false })
                                }
                            }
                        }
                    }
                    WrappingOptions {
                        FilterChip(folderFilter == null, { folderFilter = null }, label = { Text("全部文件夹") })
                        (folders + docs.mapNotNull { it.folder?.takeIf(String::isNotBlank) }).distinct().sorted().forEach { folder ->
                            FilterChip(folderFilter == folder, { folderFilter = folder }, label = { Text(folder) })
                        }
                    }
                    if (selectedIds.isNotEmpty()) WrappingOptions {
                        Text("已选择${selectedIds.size}份")
                        TextButton({ batchMode = "folder"; batchValue = "" }) { Text("移动") }
                        TextButton({ batchMode = "tags"; batchValue = "" }) { Text("加标签") }
                        TextButton({ batchMode = "trash" }) { Text("回收站") }
                        TextButton({ selectedIds = emptyList() }) { Text("取消选择") }
                    }
                }
                val displayed = if (tab == 0 && query.isBlank()) visibleDocs.take(5) else visibleDocs
                if (displayed.isEmpty()) item { InfoCard("从第一份文件开始", "拍照扫描、导入文件，或新建 Word、表格和演示文稿。", R.drawable.ic_ui_folder) }
                if (layout == "grid" && tab == 1 && selectedIds.isEmpty()) items(displayed.chunked(2), key = { it.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { doc -> DocumentGridCard(doc, { onDocClick(doc) }, { menuDoc = doc }, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else items(displayed, key = { it.id }) { doc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedIds.isNotEmpty()) Checkbox(doc.id in selectedIds, { selectedIds = if (doc.id in selectedIds) selectedIds - doc.id else selectedIds + doc.id })
                        RecentDocumentRow(doc, { if (selectedIds.isEmpty()) onDocClick(doc) else selectedIds = if (doc.id in selectedIds) selectedIds - doc.id else selectedIds + doc.id }, { menuDoc = doc }, Modifier.weight(1f))
                    }
                }
            }
            if (tab == 2) item { ToolGrid(onToolClick) }
            if (tab == 3) {
                item { InfoCard("文件留在本机", "扫描、编辑与已下载模型的处理都在本机完成。", R.drawable.ic_tool_lock) }
                val actions = listOf("模型管理" to onModelsClick, "工具任务" to onToolTasks, "文档整理与备份" to onLibraryWorkbench,
                    "导出记录" to onOutputHistoryClick, "回收站" to onTrashClick, "存储空间" to onStorage, "设置" to onSettingsClick)
                items(actions) { (title, action) -> ListItem(headlineContent = { Text(title) }, trailingContent = { AppIcon(R.drawable.ic_chevron_right) }, modifier = Modifier.clickable(onClick = action)) }
            }
        }
    }

    if (newFolder || batchMode != null) AlertDialog(onDismissRequest = { if (!batchSaving) { newFolder = false; batchMode = null } },
        title = { Text(if (newFolder) "新建文件夹" else when(batchMode) { "folder" -> "移动所选文档"; "tags" -> "添加标签"; else -> "移到回收站？" }) },
        text = { if (batchMode == "trash") Text("所选${selectedIds.size}份文档可从回收站恢复。")
            else Column { OutlinedTextField(batchValue, { batchValue = it.take(if (batchMode == "tags") 300 else 80); folderError = "" }, label = { Text(if (batchMode == "tags") "标签，多个用逗号分开" else "文件夹名称") }); if (folderError.isNotBlank()) Text(folderError, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({
            if (newFolder) runCatching { com.localdoc.scanner.data.LibraryFolders.add(context, batchValue); folders = com.localdoc.scanner.data.LibraryFolders.all(context) }.onSuccess { newFolder = false }.onFailure { folderError = it.message.orEmpty() }
            else {
                val mode = batchMode ?: return@TextButton
                val ids = selectedIds.toList(); val input = batchValue
                batchSaving = true
                scope.launch {
                    try {
                        if (onBatchDocuments != null) onBatchDocuments(ids,mode,input)
                        else docs.filter { it.id in ids }.forEach { doc -> when(mode) {
                            "folder" -> onOrganizeDoc(doc,input.trim().takeIf(String::isNotBlank),doc.tags)
                            "tags" -> onOrganizeDoc(doc,doc.folder,(doc.tags+","+input).split(',', '，').map(String::trim).filter(String::isNotBlank).distinct().joinToString(","))
                            "trash" -> onTrashDoc(doc)
                        } }
                        folders = com.localdoc.scanner.data.LibraryFolders.all(context); selectedIds = emptyList(); batchMode = null
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; folderError = "批量操作失败，所选文档仍保留：${e.message}" }
                    finally { batchSaving = false }
                }
            }
        }, enabled = !batchSaving && (batchMode == "trash" || batchValue.isNotBlank())) { Text(if (batchSaving) "保存中…" else "确定") } }, dismissButton = { TextButton({ newFolder = false; batchMode = null }, enabled = !batchSaving) { Text("取消") } })

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
                    if (doc.kind == "FILE") doc.filePath.substringAfterLast('.').uppercase() else "${doc.pageCount} 页",
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
                ListItem(headlineContent = { Text("文档信息与来源") }, modifier = Modifier.clickable { onSourceInfo(doc); menuDoc = null })
                ListItem(headlineContent = { Text(if (doc.favorite) "取消收藏" else "收藏文档") }, modifier = Modifier.clickable { onFavoriteDoc(doc); menuDoc = null })
                ListItem(headlineContent = { Text("选择多份文档") }, modifier = Modifier.clickable { selectedIds = listOf(doc.id); tab = 1; menuDoc = null })
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
