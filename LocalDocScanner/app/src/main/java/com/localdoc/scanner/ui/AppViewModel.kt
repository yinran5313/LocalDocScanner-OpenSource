package com.localdoc.scanner.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.data.DocRepository
import com.localdoc.scanner.data.DraftPage
import com.localdoc.scanner.data.DraftStore
import com.localdoc.scanner.data.FileStore
import com.localdoc.scanner.data.ScanDraft
import com.localdoc.scanner.data.db.PageEntity
import com.localdoc.scanner.edit.EditRecipe
import com.localdoc.scanner.edit.EditResult
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.ocr.PaddleOcrEngine
import com.localdoc.scanner.model.FileKind
import com.localdoc.scanner.model.ToolEntry
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


enum class EditorReturn { CAPTURE, SESSION, DOCUMENT }

data class EditTarget(
    val sourcePath: String,
    val pageIndex: Int,
    val initialRecipe: EditRecipe? = null,
    val draftPageId: String? = null,
    val documentPageId: String? = null,
    val returnTo: EditorReturn
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repo = DocRepository(application)

    val docs: StateFlow<List<DocItem>> = repo.observeDocs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashed: StateFlow<List<DocItem>> = repo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var draft: ScanDraft = DraftStore.load(app)
    var draftProblem by mutableStateOf(draft.recoveryError)
        private set
    private val _session = MutableStateFlow(draft.pages)
    val session: StateFlow<List<DraftPage>> = _session.asStateFlow()
    private val _draftTitle = MutableStateFlow(draft.title)
    val draftTitle: StateFlow<String> = _draftTitle.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    var pendingTool: ToolEntry? = null
    var toolRequest by mutableStateOf(com.localdoc.scanner.data.ToolDrafts.restoreRequest(application))
        private set
    var editTarget by mutableStateOf<EditTarget?>(null)
        private set
    var appendDocId by mutableStateOf(draft.appendDocId)
        private set
    private var importQueue: List<String> = emptyList()
    private var retakeTemplate: EditTarget? = null
    val isRetaking: Boolean get() = retakeTemplate != null
    var captureProcessing by mutableStateOf(false)
        private set
    fun openToolWithoutInput(tool: ToolEntry) {
        require(tool.accept == FileKind.NONE)
        toolRequest = ToolRequest(tool, emptyList(), emptyList())
        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest)
    }
    fun notify(text: String) {
        viewModelScope.launch { _messages.emit(text) }
    }

    suspend fun importLibraryFile(uri: Uri, titleOverride: String? = null): DocItem = withContext(Dispatchers.IO) {
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "文档"
        val id = repo.importFile(uri, titleOverride ?: name, app.contentResolver.getType(uri).orEmpty())
        repo.getItem(id) ?: error("导入未完成")
    }
    suspend fun newOffice(extension: String, title: String): DocItem {
        val id = repo.createOffice(extension, title)
        return repo.getItem(id) ?: error("新建未完成")
    }

    fun startNewScan(force: Boolean = false) {
        if (!force && draftProblem.isNotBlank()) { notify(draftProblem); return }
        if (captureProcessing) { notify("正在保存拍摄页，请稍候"); return }
        draft = DraftStore.start(app)
        appendDocId = null
        importQueue = emptyList()
        retakeTemplate = null
        editTarget = null
        syncDraft()
    }

    fun resumeDraft() {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候"); return }
        draft = DraftStore.load(app)
        appendDocId = draft.appendDocId
        syncDraft()
    }

    fun discardDraft() = startNewScan(force = true)

    fun startAppend(docId: String) {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候"); return }
        draft = DraftStore.start(app, docId)
        appendDocId = docId
        importQueue = emptyList()
        retakeTemplate = null
        syncDraft()
    }

    fun editShot(path: String, index: Int) {
        val template = retakeTemplate
        editTarget = if (template == null) {
            EditTarget(path, index, returnTo = EditorReturn.CAPTURE)
        } else {
            template.copy(sourcePath = path, initialRecipe = null)
        }
        retakeTemplate = null
    }
    val captureProfile: String get() = draft.captureProfile
    fun startProfile(profile: String): Boolean {
        if (session.value.isNotEmpty() || draftProblem.isNotBlank() || captureProcessing) { notify("请先完成当前草稿，再开始其它拍摄模式"); return false }
        require(profile in setOf("ppt", "whiteboard", "handwriting"))
        startNewScan(); draft = DraftStore.setProfile(app, draft, profile)
        draft = DraftStore.setTitle(app, draft, when(profile) { "ppt" -> "PPT采集"; "whiteboard" -> "白板笔记"; else -> "手写笔记" })
        syncDraft(); return true
    }

    fun appendContinuousShot(file: File, book: Boolean) {
        if (captureProcessing) { notify("上一张仍在处理，请稍候"); return }
        captureProcessing = true
        viewModelScope.launch {
            try {
                val notes = withContext(Dispatchers.IO) {
                    com.localdoc.scanner.capture.ContinuousCaptureProcessor.prepare(app, file, book, draft.captureProfile).use {
                        draft = DraftStore.addBatch(app, draft, it.pages)
                        it.notes
                    }
                }
                syncDraft()
                file.delete()
                notify(if (notes.isEmpty()) "已加入${if (book) 2 else 1}页，可继续换页拍摄" else notes.joinToString("；"))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep a failed shot in the durable inbox rather than losing it with cache eviction.
                val recovery = withContext(Dispatchers.IO) { runCatching {
                    file.copyTo(File(FileStore.exportDir(app), "连拍恢复_${java.util.UUID.randomUUID()}.jpg")).also {
                        com.localdoc.scanner.output.OutputHistoryStore.recordGenerated(app, it, "image/jpeg")
                    }
                }.getOrNull() }
                notify("连拍处理失败，${if (recovery != null) "原图在导出记录" else "原图仍在临时拍摄目录，请重新拍摄"}：${e.message}")
            } finally { captureProcessing = false }
        }
    }

    fun editDraftPage(page: DraftPage, index: Int) {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候再编辑"); return }
        editTarget = EditTarget(
            sourcePath = page.sourcePath,
            pageIndex = index,
            initialRecipe = page.recipe,
            draftPageId = page.id,
            returnTo = EditorReturn.SESSION
        )
    }

    suspend fun setFavorite(id: String, value: Boolean) = repo.setFavorite(id, value)

    fun editDocumentPage(page: PageEntity, index: Int) {
        editTarget = EditTarget(
            sourcePath = page.sourcePath.ifBlank { page.filePath },
            pageIndex = index,
            initialRecipe = EditRecipe(
                quarterTurns = page.quarterTurns,
                corners = EditRecipe.decodeCorners(page.cropPoints),
                filter = runCatching { ScanFilter.valueOf(page.filter) }.getOrDefault(ScanFilter.ORIGINAL),
                brightness = page.brightness,
                contrast = page.contrast,
                fineRotation = page.fineRotation, cropRatio = page.cropRatio
            ),
            documentPageId = page.id,
            returnTo = EditorReturn.DOCUMENT
        )
    }

    fun requestRetake() {
        retakeTemplate = editTarget
        editTarget = null
    }

    fun cancelRetake() {
        retakeTemplate = null
    }

    suspend fun prepareImports(uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        val paths = uris.mapIndexedNotNull { index, uri ->
            val bitmap = ImageIo.loadFromUri(app, uri, 4000) ?: return@mapIndexedNotNull null
            val target = File(FileStore.draftInboxDir(app), "import_${System.currentTimeMillis()}_$index.jpg")
            val saved = ImageIo.saveJpeg(bitmap, target, 96)
            bitmap.recycle()
            target.takeIf { saved }?.absolutePath
        }
        withContext(Dispatchers.Main) {
            val skipped = uris.size - paths.size
            if (skipped > 0) notify("有 $skipped 张图片无法读取，已跳过")
            if (paths.isEmpty()) {
                notify("没有读到可用的图片")
                false
            } else {
                importQueue = paths
                editTarget = EditTarget(paths.first(), draft.pages.size, returnTo = EditorReturn.SESSION)
                true
            }
        }
    }

    /** 返回null表示多图导入还有下一张，编辑页原地切换。 */
    suspend fun confirmEdit(result: EditResult): EditorReturn? = withContext(Dispatchers.IO) {
        check(!captureProcessing) { "正在保存连拍页面，请稍候再提交编辑" }
        val target = editTarget ?: return@withContext EditorReturn.SESSION
        if (target.documentPageId != null) {
            repo.updatePage(target.documentPageId, result)
        } else if (target.draftPageId != null) {
            draft = DraftStore.replace(app, draft, target.draftPageId, result)
        } else {
            draft = DraftStore.add(app, draft, result)
        }
        result.sourceFile.takeIf {
            it.absolutePath.startsWith(app.cacheDir.absolutePath) ||
                it.absolutePath.startsWith(FileStore.draftInboxDir(app).absolutePath)
        }?.delete()
        result.renderedFile.takeIf { it.absolutePath.startsWith(app.cacheDir.absolutePath) }?.delete()
        withContext(Dispatchers.Main) { syncDraft() }

        if (importQueue.isNotEmpty()) {
            importQueue = importQueue.drop(1)
            if (importQueue.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    editTarget = EditTarget(importQueue.first(), draft.pages.size, returnTo = EditorReturn.SESSION)
                }
                return@withContext null
            }
        }
        withContext(Dispatchers.Main) { editTarget = null }
        target.returnTo
    }

    fun cancelEdit(deleteTransientSource: Boolean = true) {
        val target = editTarget
        if (deleteTransientSource && target?.draftPageId == null && target?.documentPageId == null) {
            File(target?.sourcePath.orEmpty()).takeIf {
                it.exists() && (it.absolutePath.startsWith(app.cacheDir.absolutePath) ||
                    it.absolutePath.startsWith(FileStore.draftInboxDir(app).absolutePath))
            }?.delete()
        }
        importQueue = emptyList()
        editTarget = null
    }

    fun removeSessionPage(pageId: String) {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候"); return }
        draft = DraftStore.remove(app, draft, pageId)
        syncDraft()
    }

    fun moveSessionPage(from: Int, to: Int) {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候"); return }
        draft = DraftStore.move(app, draft, from, to)
        syncDraft()
    }

    fun setDraftTitle(title: String) {
        if (captureProcessing) return
        draft = DraftStore.setTitle(app, draft, title)
        syncDraft()
    }

    suspend fun commitSession(title: String): String? = withContext(Dispatchers.IO) {
        if (captureProcessing) { notify("正在保存拍摄页，请稍候再完成文档"); return@withContext null }
        if (draft.recoveryError.isNotBlank()) { notify(draft.recoveryError); return@withContext null }
        if (draft.pages.isEmpty()) return@withContext null
        val missing = draft.pages.mapIndexedNotNull { index, page ->
            if (!File(page.sourcePath).isFile || !File(page.renderedPath).isFile) index + 1 else null
        }
        if (missing.isNotEmpty()) { notify("草稿第${missing.joinToString()}页缺失，已停止保存，原稿保留"); return@withContext null }
        val existing = draft.appendDocId
        val id = existing ?: repo.createDoc(title.ifBlank { "未命名文档" })
        if (existing == null) draft = DraftStore.setAppendDoc(app, draft, id)
        draft.pages.forEach { repo.appendPage(id, it) }
        DraftStore.clear(app)
        draft = ScanDraft()
        withContext(Dispatchers.Main) {
            appendDocId = null
            syncDraft()
        }
        id
    }

    fun onFilesPicked(tool: ToolEntry, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val files = mutableListOf<File>()
            val names = mutableListOf<String>()
            val failures=mutableListOf<String>()
            uris.forEachIndexed { index,uri ->
                try {
                    val name=com.localdoc.scanner.output.OutputHistoryStore.displayName(app,uri).ifBlank { "file_$index" }
                    val mime=app.contentResolver.getType(uri).orEmpty()
                    val asImage=tool.accept==FileKind.IMAGE || tool.accept==FileKind.ANY && mime.startsWith("image/")
                    val dir=FileStore.importDir(app)
                    val dest=if(asImage) ImageIo.stageImage(app,uri,dir) else {
                        val extension=if(tool.accept==FileKind.PDF) "pdf" else name.substringAfterLast('.',"bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
                        File(dir,"imp_${java.util.UUID.randomUUID()}.$extension").also { require(ImageIo.copyBytes(app,uri,it)) { "无法读取文件" } }
                    }
                    files+=dest;names+=name
                } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;failures+="第${index+1}个文件：${e.message}" }
            }
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) notify(failures.firstOrNull() ?: "没有读到可用的文件") else { toolRequest = ToolRequest(tool, files, names); com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest); if(failures.isNotEmpty())notify("已读入${files.size}份；${failures.joinToString("；")}") }
            }
        }
    }

    fun openSavedTool(request: ToolRequest) {
        toolRequest = request
        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, request)
    }

    fun closeTool() {
        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, null)
        toolRequest = null
        pendingTool = null
    }

    fun replaceToolInput(source: File, workingCopy: File) {
        val current = toolRequest ?: return
        if (!workingCopy.isFile || current.files.none { it == source }) return
        toolRequest = current.copy(files = current.files.map { if (it == source) workingCopy else it })
        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest)
    }

    suspend fun openExternalPdfForEditing(source: File, displayName: String) {
        openExternalTool(source,displayName,ToolEntry("pdf_office","PDF编辑",FileKind.PDF))
    }
    suspend fun openExternalTool(source: File, displayName: String, tool: ToolEntry) {
        val target = withContext(Dispatchers.IO) {
            require(source.isFile && source.length() in 1..2L*1024*1024*1024 && FileStore.importDir(app).usableSpace>source.length()+16L*1024*1024) { "输入文件无效、过大或空间不足" }
            val extension=source.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) } ?: "bin"
            File(FileStore.importDir(app), "external_${java.util.UUID.randomUUID()}.$extension").also {
                com.localdoc.scanner.util.AtomicFiles.copy(source,it)
            }
        }
        toolRequest = ToolRequest(
            tool,
            listOf(target),
            listOf(displayName)
        )
        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest)
    }

    suspend fun saveFilesAsDoc(title: String, files: List<File>): String? {
        if (files.isEmpty()) return null
        val id = repo.createDoc(title)
        files.forEach { repo.appendProcessedPage(id, it) }
        return id
    }

    suspend fun rename(docId: String, title: String) = repo.rename(docId, title)
    suspend fun batchDocuments(ids: List<String>, action: String, value: String) = repo.batchDocuments(ids,action,value)
    suspend fun changeFolder(oldName: String, newName: String?) = repo.changeFolder(oldName,newName)
    suspend fun sourceInfo(id: String) = repo.sourceInfo(id)
    suspend fun setOrganization(docId: String, folder: String?, tags: String) = repo.setOrganization(docId, folder, tags)
    suspend fun updateDocumentOcrText(docId: String, text: String) = repo.setOcrText(docId, text)
    suspend fun trash(docId: String) = repo.trash(docId)
    suspend fun restoreDoc(docId: String) = repo.restoreDoc(docId)
    suspend fun deleteForever(docId: String) = repo.deleteForever(docId)
    suspend fun pages(docId: String): List<PageEntity> = repo.pages(docId)
    suspend fun trashPage(docId: String, pageId: String) = repo.trashPage(docId, pageId)
    suspend fun restorePage(docId: String, pageId: String) = repo.restorePage(docId, pageId)
    suspend fun duplicatePage(docId: String, pageId: String) = repo.duplicatePage(docId, pageId)
    suspend fun movePage(docId: String, from: Int, to: Int) = repo.movePage(docId, from, to)
    suspend fun applyEnhancementToAll(docId: String, filter: ScanFilter, brightness: Float, contrast: Float) =
        repo.applyEnhancementToAll(docId, filter, brightness, contrast)
    suspend fun backupLibrary(uri: Uri) = repo.backupLibrary(uri)
    suspend fun backupEncryptedLibrary(uri: Uri, password: CharArray) = repo.backupLibrary(uri, password)
    suspend fun restoreLibrary(uri: Uri) = repo.restoreLibrary(uri)
    suspend fun restoreEncryptedLibrary(uri: Uri, password: CharArray) = repo.restoreEncryptedLibrary(uri, password)
    suspend fun exportPdf(docId: String, output: File, size: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        repo.exportPdf(docId, output, size, maxImageSide)
    suspend fun exportPdf(docId: String, output: Uri, size: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        repo.exportPdf(docId, output, size, maxImageSide)
    suspend fun exportSearchablePdf(docId: String, output: File, pageSize: PdfExporter.PageSize = PdfExporter.PageSize.A4, maxImageSide: Int = 3200): Boolean = exportGuard(docId) { repo.exportSearchablePdf(docId, output, pageSize, maxImageSide) }
    suspend fun exportSearchablePdf(docId: String, output: Uri, pageSize: PdfExporter.PageSize = PdfExporter.PageSize.A4, maxImageSide: Int = 3200): Boolean = exportGuard(docId) { repo.exportSearchablePdf(docId, output, pageSize, maxImageSide) }
    private suspend fun exportGuard(docId: String, export: suspend () -> Boolean): Boolean {
        val error = repo.exportProblem(docId)
        if (error != null) { notify(error); return false }
        return export()
    }
    suspend fun exportJpegs(docId: String, treeUri: Uri, baseName: String): Int = repo.exportJpegs(docId, treeUri, baseName)
    suspend fun correctOcrLine(pageId: String, line: Int, text: String, expectedVersion: Long) = repo.correctOcrLine(pageId, line, text, expectedVersion)
    suspend fun renderedFiles(docId: String): List<File> = repo.renderedFiles(docId)
    suspend fun exportLongImage(docId: String, output: File): Boolean = repo.exportLongImage(docId, output)

    private fun syncDraft() {
        draftProblem = draft.recoveryError
        _session.value = draft.pages
        _draftTitle.value = draft.title
        appendDocId = draft.appendDocId
    }
}

