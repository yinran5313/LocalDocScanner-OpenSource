package com.localdoc.scanner.ui.nav

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localdoc.scanner.capture.CaptureScreen
import com.localdoc.scanner.edit.EditScreen
import com.localdoc.scanner.model.FileKind
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.EditorReturn
import com.localdoc.scanner.ui.doc.DocDetailScreen
import com.localdoc.scanner.ui.home.HomeScreen
import com.localdoc.scanner.ui.output.OutputHistoryScreen
import com.localdoc.scanner.ui.session.SessionScreen
import com.localdoc.scanner.ui.settings.SettingsScreen
import com.localdoc.scanner.ui.trash.TrashScreen
import com.localdoc.scanner.ui.tools.ToolScreen
import com.localdoc.scanner.external.ExternalOpenBus
import com.localdoc.scanner.external.ExternalFileScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Route {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val EDIT = "edit"
    const val SESSION = "session"
    const val TOOL = "tool"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val OUTPUT_HISTORY = "output-history"
    const val DOC = "doc/{docId}"
    fun doc(id: String) = "doc/$id"
}

private fun mimeFor(kind: FileKind): Array<String> = when (kind) {
    FileKind.IMAGE -> arrayOf("image/*")
    FileKind.PDF -> arrayOf("application/pdf")
    FileKind.ANY -> arrayOf("*/*")
    FileKind.NONE -> emptyArray()
}

@Composable
fun AppNav(modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs by vm.docs.collectAsState()
    val trashed by vm.trashed.collectAsState()
    val session by vm.session.collectAsState()
    val draftTitle by vm.draftTitle.collectAsState()
    val editTarget = vm.editTarget
    val external by ExternalOpenBus.current.collectAsState()
    var creating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    var sourceInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    sourceInfo?.let { text -> androidx.compose.material3.AlertDialog(onDismissRequest = { sourceInfo = null }, title = { androidx.compose.material3.Text("文档信息") }, text = { androidx.compose.foundation.text.selection.SelectionContainer { androidx.compose.material3.Text(text) } }, confirmButton = { androidx.compose.material3.TextButton({ sourceInfo = null }) { androidx.compose.material3.Text("关闭") } }) }
    if (creating) com.localdoc.scanner.ui.home.NewDocumentDialog(onDismiss = { creating = false }, onCreate = { extension, name ->
        try { val doc = vm.newOffice(extension, name); creating = false; ExternalOpenBus.offerLibrary(context, doc) }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("新建失败：${e.message}") }
    })


    val draftBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { out -> java.util.zip.ZipOutputStream(out).use { zip ->
                        val root = com.localdoc.scanner.data.FileStore.draftDir(context)
                        root.walkTopDown().filter { it.isFile }.forEach { file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                            file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    } } ?: error("无法创建备份")
                }
                vm.notify("原始草稿已备份，应用内草稿仍保留")
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("草稿备份失败：${e.message}") }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                if (vm.prepareImports(uris)) navController.navigate(Route.EDIT)
            }
        }
    }
    val openFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try { ExternalOpenBus.offerLibrary(context, vm.importLibraryFile(uri)); vm.notify("已导入文档库，原件保留") }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("导入失败：${e.message}") }
            }
        }
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val tool = vm.pendingTool ?: return@rememberLauncherForActivityResult
        if (uri != null) vm.onFilesPicked(tool, listOf(uri))
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val tool = vm.pendingTool ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) vm.onFilesPicked(tool, uris)
    }

    androidx.compose.foundation.layout.Box(modifier) {
    NavHost(navController = navController, startDestination = Route.HOME, modifier = if (external != null) modifier.clearAndSetSemantics {} else modifier) {
        composable(Route.HOME) {
            HomeScreen(
                docs = docs,
                draftCount = session.size,
                draftProblem = vm.draftProblem,
                onBackupDraft = { draftBackup.launch("扫描草稿原始文件.zip") },
                onCaptureClick = {
                    if (vm.draftProblem.isNotBlank()) vm.notify(vm.draftProblem)
                    else if (session.isNotEmpty()) {
                        vm.resumeDraft()
                        navController.navigate(Route.SESSION)
                    } else {
                        vm.startNewScan()
                        navController.navigate(Route.CAPTURE)
                    }
                },
                onImportClick = {
                    if (vm.draftProblem.isNotBlank()) vm.notify(vm.draftProblem)
                    else { if (session.isEmpty()) vm.startNewScan(); importPicker.launch(arrayOf("image/*")) }
                },
                onOpenFileClick = { openFilePicker.launch(arrayOf("*/*")) },
                onResumeDraft = { vm.resumeDraft(); navController.navigate(Route.SESSION) },
                onDiscardDraft = { vm.discardDraft() },
                onTrashClick = { navController.navigate(Route.TRASH) },
                onOutputHistoryClick = { navController.navigate(Route.OUTPUT_HISTORY) },
                onStorage = { navController.navigate("storage") },
                onToolTasks = { navController.navigate("tool-tasks") },
                onLibraryWorkbench = { navController.navigate("library-workbench") },
                onSettingsClick = { navController.navigate(Route.SETTINGS) },
                onToolClick = { tool ->
                    vm.pendingTool = tool
                    if (tool.id == "semantic_search") navController.navigate("semantic-search")
                    else if (tool.id == "barcode_live") navController.navigate("barcode-live")
                    else if (tool.id == "watermark_camera") navController.navigate("watermark-camera")
                    else if (tool.id == "card_wallet") navController.navigate("card-wallet")
                    else if (tool.id == "sign_library") navController.navigate("signature-library")
                    else if (tool.id.startsWith("scan_")) { if (vm.startProfile(tool.id.removePrefix("scan_"))) navController.navigate(Route.CAPTURE) }
                    else if (tool.accept == FileKind.NONE) vm.openToolWithoutInput(tool)
                    else if (tool.allowMultiple) multiPicker.launch(mimeFor(tool.accept)) else singlePicker.launch(mimeFor(tool.accept))
                },
                onDocClick = { doc ->
                    if (doc.kind == "FILE") try { ExternalOpenBus.offerLibrary(context, doc) } catch (e: Exception) { vm.notify(e.message.orEmpty()) }
                    else navController.navigate(Route.doc(doc.id))
                },
                onModelsClick = { navController.navigate(Route.MODELS) },
                onNewDocument = { creating = true },
                onManageFolders = { navController.navigate("folder-management") },
                onSourceInfo = { doc -> scope.launch { runCatching { vm.sourceInfo(doc.id) }.onSuccess { sourceInfo = it }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; vm.notify("读取信息失败：${it.message}") } } },
                onBatchDocuments = vm::batchDocuments,
                onRenameDoc = { doc, title -> scope.launch { vm.rename(doc.id, title) } },
                onOrganizeDoc = { doc, folder, tags ->
                    scope.launch { vm.setOrganization(doc.id, folder, tags) }
                },
                onTrashDoc = { doc -> scope.launch { vm.trash(doc.id) } },
                onFavoriteDoc = { doc -> scope.launch { vm.setFavorite(doc.id, !doc.favorite) } }
            )
        }

        composable(Route.MODELS) { com.localdoc.scanner.models.ModelsScreen(onBack = { navController.popBackStack() }) }
        composable("folder-management") { com.localdoc.scanner.ui.home.FolderManagementScreen(vm) { navController.popBackStack() } }
        composable("card-wallet") { com.localdoc.scanner.ui.tools.CardWalletScreen(vm, { navController.popBackStack() }, { id -> navController.navigate(Route.doc(id)) }, {
            vm.pendingTool = com.localdoc.scanner.model.TOOL_ENTRIES.first { it.id == "card" }
            multiPicker.launch(arrayOf("image/*"))
        }) }
        composable("barcode-live") { com.localdoc.scanner.barcode.LiveBarcodeScreen { navController.popBackStack() } }
        composable("signature-library") { com.localdoc.scanner.ui.tools.SignatureLibraryScreen({ navController.popBackStack() }) }
        composable("watermark-camera") { CaptureScreen(pageCount = 0, allowContinuous = false,
            onCaptured = { file -> scope.launch {
                try { val durable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File(com.localdoc.scanner.data.FileStore.importDir(context), "watermark-${java.util.UUID.randomUUID()}.jpg").also { com.localdoc.scanner.util.AtomicFiles.copy(file, it); it.setLastModified(file.lastModified()) }
                }; vm.openSavedTool(com.localdoc.scanner.ui.ToolRequest(com.localdoc.scanner.model.ToolEntry("watermark_photo", "水印照片", FileKind.IMAGE), listOf(durable), listOf("拍摄照片.jpg"))) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; vm.notify("照片保留，暂未进入编辑：${e.message}") }
            } },
            onFinish = { navController.popBackStack() }, onBack = { navController.popBackStack() }) }
        composable("semantic-search") { com.localdoc.scanner.ui.home.SemanticSearchScreen({ navController.popBackStack() }) { hit ->
            val doc = docs.firstOrNull { if (hit.key.startsWith("scan:")) it.id == hit.key.removePrefix("scan:") else it.filePath == hit.path }
            if (doc?.kind == "FILE") ExternalOpenBus.offerLibrary(context, doc)
            else if (doc != null) navController.navigate(Route.doc(doc.id))
            else if (hit.path.isNotBlank()) {
                val file = java.io.File(hit.path)
                ExternalOpenBus.offer(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), com.localdoc.scanner.output.OutputHistoryStore.mimeFor(file))
                }, context.contentResolver)
            }
        } }

        composable(Route.CAPTURE) {
            CaptureScreen(
                pageCount = if (vm.isRetaking) 0 else session.size,
                profile = vm.captureProfile,
                processing = vm.captureProcessing,
                allowContinuous = !vm.isRetaking,
                onContinuousCaptured = vm::appendContinuousShot,
                onCaptured = { file ->
                    val replacing = vm.isRetaking
                    vm.editShot(file.absolutePath, session.size)
                    navController.navigate(Route.EDIT) {
                        if (replacing) popUpTo(Route.CAPTURE) { inclusive = true }
                    }
                },
                onFinish = { navController.navigate(Route.SESSION) },
                onBack = {
                    if (vm.isRetaking) {
                        vm.cancelRetake()
                        navController.popBackStack()
                    } else if (session.isNotEmpty()) {
                        navController.navigate(Route.SESSION)
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(Route.EDIT) {
            val target = editTarget
            if (target == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                key(target.sourcePath, target.draftPageId, target.documentPageId) {
                    EditScreen(
                        sourcePath = target.sourcePath,
                        pageIndex = target.pageIndex,
                        initialRecipe = target.initialRecipe,
                        defaultFilter = when(vm.captureProfile) { "ppt" -> com.localdoc.scanner.cv.ScanFilter.PROJECTOR; "handwriting" -> com.localdoc.scanner.cv.ScanFilter.GRAY; else -> com.localdoc.scanner.cv.ScanFilter.AUTO },
                        onConfirm = { result ->
                            try {
                                when (vm.confirmEdit(result)) {
                                    null -> Unit
                                    EditorReturn.CAPTURE -> navController.popBackStack()
                                    EditorReturn.SESSION -> navController.navigate(Route.SESSION) {
                                        popUpTo(Route.EDIT) { inclusive = true }
                                    }
                                    EditorReturn.DOCUMENT -> navController.popBackStack()
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                vm.notify("保存失败：${e.message}，原页和草稿保留")
                            }
                        },
                        onRetake = {
                            if (target.draftPageId != null || target.documentPageId != null) {
                                vm.requestRetake()
                                navController.navigate(Route.CAPTURE) {
                                    popUpTo(Route.EDIT) { inclusive = true }
                                }
                            } else {
                                vm.cancelEdit()
                                navController.popBackStack()
                            }
                        },
                        onBack = {
                            vm.cancelEdit()
                            navController.popBackStack()
                        }
                    )
                }
            }
        }

        composable(Route.SESSION) {
            SessionScreen(
                pages = session,
                title = draftTitle.ifBlank { com.localdoc.scanner.data.AppPreferences(context).documentName() },
                isAppending = vm.appendDocId != null,
                onTitleChange = vm::setDraftTitle,
                onBackHome = { navController.navigate(Route.HOME) { popUpTo(Route.HOME) { inclusive = true } } },
                onAddCamera = { navController.navigate(Route.CAPTURE) },
                onAddImages = { importPicker.launch(arrayOf("image/*")) },
                onEdit = { page, index -> vm.editDraftPage(page, index); navController.navigate(Route.EDIT) },
                onDelete = { vm.removeSessionPage(it.id) },
                onMove = vm::moveSessionPage,
                onSave = {
                    scope.launch {
                        val docId = vm.commitSession(draftTitle.ifBlank { com.localdoc.scanner.data.AppPreferences(context).documentName() })
                        if (docId != null) {
                            navController.navigate(Route.doc(docId)) { popUpTo(Route.HOME) }
                        }
                    }
                }
            )
        }

        composable(Route.DOC, arguments = listOf(navArgument("docId") { type = NavType.StringType })) { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            DocDetailScreen(
                docId = docId,
                vm = vm,
                onBack = { navController.popBackStack() },
                onAddPage = {
                    if (session.isNotEmpty()) {
                        vm.notify("请先完成或放弃当前草稿，再给这份文档加页")
                        navController.navigate(Route.SESSION)
                    } else {
                        vm.startAppend(docId)
                        navController.navigate(Route.CAPTURE)
                    }
                },
                onEditPage = { page, index -> vm.editDocumentPage(page, index); navController.navigate(Route.EDIT) }
            )
        }

        composable(Route.TRASH) {
            TrashScreen(
                items = trashed,
                onBack = { navController.popBackStack() },
                onRestore = { scope.launch { vm.restoreDoc(it.id) } },
                onDeleteForever = { scope.launch { vm.deleteForever(it.id) } }
            )
        }

        composable(Route.SETTINGS) { SettingsScreen(vm = vm, onBack = { navController.popBackStack() }) }

        composable("storage") { com.localdoc.scanner.ui.settings.StorageScreen(onBack = { navController.popBackStack() }) }
        composable("tool-tasks") {
            com.localdoc.scanner.ui.tools.ToolTasksScreen(onBack = { navController.popBackStack() }, onOpen = { spec ->
                vm.openSavedTool(spec.request)
                navController.navigate(Route.TOOL) { launchSingleTop = true }
            })
        }
        composable(Route.OUTPUT_HISTORY) { OutputHistoryScreen(onBack = { navController.popBackStack() }) }
        composable("library-workbench") {
            com.localdoc.scanner.ui.home.LibraryWorkbench(vm, { navController.popBackStack() }, { id ->
                val doc = docs.firstOrNull { it.id == id }
                if (doc?.kind == "FILE") ExternalOpenBus.offerLibrary(context, doc)
                else navController.navigate(Route.doc(id))
            })
        }

        composable(Route.TOOL) {
            ToolScreen(
                vm = vm,
                onBack = { vm.closeTool(); navController.popBackStack() },
                onOpenDoc = { id ->
                    vm.closeTool()
                    navController.navigate(Route.doc(id)) { popUpTo(Route.HOME) }
                }
            )
        }
    }

    if (external != null) {
        androidx.activity.compose.BackHandler { ExternalOpenBus.close() }
        key(external!!.uri, external!!.libraryDocId) {
            ExternalFileScreen(external!!, ExternalOpenBus::close, onEditPdf = { file, name -> vm.openExternalPdfForEditing(file,name); ExternalOpenBus.close() }, modifier = Modifier,
                onExtractZip={ file,name -> vm.openExternalTool(file,name,com.localdoc.scanner.model.TOOL_ENTRIES.first { it.id=="zip_import" });ExternalOpenBus.close() })
        }
    }
    }

    LaunchedEffect(vm.toolRequest) {
        if (vm.toolRequest != null) navController.navigate(Route.TOOL) { launchSingleTop = true }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}
