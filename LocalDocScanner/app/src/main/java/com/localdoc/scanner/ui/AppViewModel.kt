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

data class ToolRequest(val tool: ToolEntry, val files: List<File>, val names: List<String>)

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
    private val ocrEngine = PaddleOcrEngine(application)

    val docs: StateFlow<List<DocItem>> = repo.observeDocs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashed: StateFlow<List<DocItem>> = repo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var draft: ScanDraft = DraftStore.load(app)
    private val _session = MutableStateFlow(draft.pages)
    val session: StateFlow<List<DraftPage>> = _session.asStateFlow()
    private val _draftTitle = MutableStateFlow(draft.title)
    val draftTitle: StateFlow<String> = _draftTitle.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    var pendingTool: ToolEntry? = null
    var toolRequest by mutableStateOf<ToolRequest?>(null)
        private set
    var editTarget by mutableStateOf<EditTarget?>(null)
        private set
    var appendDocId by mutableStateOf(draft.appendDocId)
        private set
    private var importQueue: List<String> = emptyList()
    private var retakeTemplate: EditTarget? = null
    val isRetaking: Boolean get() = retakeTemplate != null

    fun notify(text: String) {
        viewModelScope.launch { _messages.emit(text) }
    }

    fun startNewScan() {
        draft = DraftStore.start(app)
        appendDocId = null
        importQueue = emptyList()
        retakeTemplate = null
        editTarget = null
        syncDraft()
    }

    fun resumeDraft() {
        draft = DraftStore.load(app)
        appendDocId = draft.appendDocId
        syncDraft()
    }

    fun discardDraft() = startNewScan()

    fun startAppend(docId: String) {
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

    fun editDraftPage(page: DraftPage, index: Int) {
        editTarget = EditTarget(
            sourcePath = page.sourcePath,
            pageIndex = index,
            initialRecipe = page.recipe,
            draftPageId = page.id,
            returnTo = EditorReturn.SESSION
        )
    }

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
                fineRotation = page.fineRotation
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
        draft = DraftStore.remove(app, draft, pageId)
        syncDraft()
    }

    fun moveSessionPage(from: Int, to: Int) {
        draft = DraftStore.move(app, draft, from, to)
        syncDraft()
    }

    fun setDraftTitle(title: String) {
        draft = DraftStore.setTitle(app, draft, title)
        syncDraft()
    }

    suspend fun commitSession(title: String): String? = withContext(Dispatchers.IO) {
        if (draft.pages.isEmpty()) return@withContext null
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
            uris.forEachIndexed { index, uri ->
                val name = uri.lastPathSegment ?: "file_$index"
                val stamp = System.currentTimeMillis()
                val dir = FileStore.importDir(app)
                val asImage = tool.accept == FileKind.IMAGE ||
                    (tool.accept == FileKind.ANY && (app.contentResolver.getType(uri) ?: "").startsWith("image/"))
                val dest = File(dir, "imp_${stamp}_$index.${if (asImage) "jpg" else "pdf"}")
                val ok = if (asImage) {
                    val bmp = ImageIo.loadFromUri(app, uri, 3200)
                    if (bmp == null) false else ImageIo.saveJpeg(bmp, dest, 94).also { bmp.recycle() }
                } else {
                    ImageIo.copyBytes(app, uri, dest)
                }
                if (ok && dest.exists()) { files.add(dest); names.add(name) }
            }
            withContext(Dispatchers.Main) {
                if (files.isEmpty()) notify("没有读到可用的文件") else toolRequest = ToolRequest(tool, files, names)
            }
        }
    }

    fun closeTool() {
        toolRequest = null
        pendingTool = null
    }

    suspend fun openExternalPdfForEditing(source: File, displayName: String) {
        val target = withContext(Dispatchers.IO) {
            val safe = displayName.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "外部PDF.pdf" }
            File(FileStore.importDir(app), "external_${System.currentTimeMillis()}_$safe").also {
                source.copyTo(it, overwrite = true)
            }
        }
        toolRequest = ToolRequest(
            ToolEntry("pdf_office", "PDF编辑", FileKind.PDF),
            listOf(target),
            listOf(displayName)
        )
    }

    suspend fun saveFilesAsDoc(title: String, files: List<File>): String? {
        if (files.isEmpty()) return null
        val id = repo.createDoc(title)
        files.forEach { repo.appendProcessedPage(id, it) }
        return id
    }

    suspend fun rename(docId: String, title: String) = repo.rename(docId, title)
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
    suspend fun restoreLibrary(uri: Uri) = repo.restoreLibrary(uri)
    suspend fun exportPdf(docId: String, output: File, size: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        repo.exportPdf(docId, output, size, maxImageSide)
    suspend fun exportPdf(docId: String, output: Uri, size: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        repo.exportPdf(docId, output, size, maxImageSide)
    suspend fun exportSearchablePdf(docId: String, output: File): Boolean = repo.exportSearchablePdf(docId, output)
    suspend fun exportSearchablePdf(docId: String, output: Uri): Boolean = repo.exportSearchablePdf(docId, output)
    suspend fun exportJpegs(docId: String, treeUri: Uri, baseName: String): Int = repo.exportJpegs(docId, treeUri, baseName)
    suspend fun renderedFiles(docId: String): List<File> = repo.renderedFiles(docId)
    suspend fun exportLongImage(docId: String, output: File): Boolean = repo.exportLongImage(docId, output)

    suspend fun recognizeDocument(docId: String, precise: Boolean): DocumentOcrResult = withContext(Dispatchers.IO) {
        val pages = repo.pages(docId)
        var lineCount = 0
        var totalMs = 0L
        var succeeded = 0
        val failures = mutableListOf<Int>()
        pages.forEachIndexed { index, page ->
            val bitmap = ImageIo.loadFromFile(File(page.filePath), if (precise) 3600 else 2400)
            if (bitmap == null) {
                failures += index + 1
            } else {
                val outcome = runCatching { ocrEngine.recognize(bitmap, precise) }.getOrNull()
                bitmap.recycle()
                if (outcome == null) {
                    failures += index + 1
                } else {
                    repo.setPageOcr(page.id, outcome.text, if (precise) "PP-OCRv6-medium" else "PP-OCRv6-tiny", outcome.boxes)
                    lineCount += outcome.lineCount
                    totalMs += outcome.totalTimeMs
                    succeeded++
                }
            }
        }
        val text = repo.rebuildDocumentOcr(docId)
        DocumentOcrResult(text, pages.size, succeeded, lineCount, totalMs, failures)
    }

    private fun syncDraft() {
        _session.value = draft.pages
        _draftTitle.value = draft.title
        appendDocId = draft.appendDocId
    }
}

data class DocumentOcrResult(
    val text: String,
    val pageCount: Int,
    val succeededPages: Int,
    val lineCount: Int,
    val totalTimeMs: Long,
    val failedPages: List<Int>
)
