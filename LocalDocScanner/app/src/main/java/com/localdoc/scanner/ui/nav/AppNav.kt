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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
    const val OUTPUT_HISTORY = "output-history"
    const val DOC = "doc/{docId}"
    fun doc(id: String) = "doc/$id"
}

private fun mimeFor(kind: FileKind): Array<String> = when (kind) {
    FileKind.IMAGE -> arrayOf("image/*")
    FileKind.PDF -> arrayOf("application/pdf")
    FileKind.ANY -> arrayOf("*/*")
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

    if (external != null) {
        ExternalFileScreen(
            external = external!!,
            onClose = ExternalOpenBus::close,
            onEditPdf = { file, name ->
                vm.openExternalPdfForEditing(file, name)
                ExternalOpenBus.close()
            },
            modifier = modifier
        )
        return
    }

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
            val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
            ExternalOpenBus.offer(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.contentResolver
            )
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

    NavHost(navController = navController, startDestination = Route.HOME, modifier = modifier) {
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
                    if (tool.allowMultiple) multiPicker.launch(mimeFor(tool.accept)) else singlePicker.launch(mimeFor(tool.accept))
                },
                onDocClick = { navController.navigate(Route.doc(it.id)) },
                onRenameDoc = { doc, title -> scope.launch { vm.rename(doc.id, title) } },
                onOrganizeDoc = { doc, folder, tags ->
                    scope.launch { vm.setOrganization(doc.id, folder, tags) }
                },
                onTrashDoc = { doc -> scope.launch { vm.trash(doc.id) } },
                onFavoriteDoc = { doc -> scope.launch { vm.setFavorite(doc.id, !doc.favorite) } }
            )
        }

        composable(Route.CAPTURE) {
            CaptureScreen(
                pageCount = if (vm.isRetaking) 0 else session.size,
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
            com.localdoc.scanner.ui.home.LibraryWorkbench(vm, { navController.popBackStack() }, { navController.navigate(Route.doc(it)) })
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

    LaunchedEffect(vm.toolRequest) {
        if (vm.toolRequest != null) navController.navigate(Route.TOOL) { launchSingleTop = true }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}
