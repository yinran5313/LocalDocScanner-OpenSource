package com.localdoc.scanner.data

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import com.localdoc.scanner.util.AtomicFiles
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.Stitch
import com.localdoc.scanner.data.db.AppDatabase
import com.localdoc.scanner.data.db.DocEntity
import com.localdoc.scanner.data.db.PageEntity
import com.localdoc.scanner.edit.EditRecipe
import com.localdoc.scanner.edit.EditResult
import com.localdoc.scanner.edit.renderProcessed
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.export.SearchablePdfExporter
import com.localdoc.scanner.export.SearchablePdfPage
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Room保存索引和编辑参数，App私有目录保存原图与派生图。 */
class DocRepository(context: Context) {

    private val app = context.applicationContext
    private val database = AppDatabase.get(app)
    private val dao = database.docDao()

    fun observeDocs(): Flow<List<DocItem>> = dao.observeDocs().map { list -> list.map { it.toItem() } }
    fun observeTrashed(): Flow<List<DocItem>> = dao.observeTrashed().map { list -> list.map { it.toItem() } }
    suspend fun search(q: String): List<DocItem> = dao.search(q).map { it.toItem() }

    suspend fun setFavorite(id: String, value: Boolean) = withContext(Dispatchers.IO) { dao.setFavorite(id, value) }

    suspend fun createDoc(title: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = "d${now.toString(36)}${(100..999).random()}"
        dao.insertDoc(DocEntity(id, title, now, now, 0, 0))
        FileStore.docDir(app, id)
        id
    }

    suspend fun appendPage(docId: String, draftPage: DraftPage) = withContext(Dispatchers.IO) {
        require(dao.getDoc(docId)?.deleted == false) { "文档不存在或已删除" }
        val pageId = "${docId}_${draftPage.id}"
        val existing = dao.getPage(pageId)
        require(existing?.deleted != true) { "此草稿页面已移入回收站，请先恢复" }
        val version = UUID.randomUUID().toString()
        val sourceTarget = File(FileStore.docDir(app, docId), "source_${pageId}_$version.jpg")
        val renderedTarget = File(FileStore.docDir(app, docId), "page_${pageId}_$version.jpg")
        var committed = false
        try {
            AtomicFiles.copy(File(draftPage.sourcePath), sourceTarget)
            AtomicFiles.copy(File(draftPage.renderedPath), renderedTarget)
            withContext(NonCancellable) { database.withTransaction {
                require(dao.getDoc(docId)?.deleted == false) { "文档已移除" }
                check(dao.getPage(pageId)?.updatedAt == existing?.updatedAt) { "页面已变化，请重新保存" }
                dao.insertPages(listOf(PageEntity(id = pageId, docId = docId,
                    pageIndex = existing?.pageIndex ?: dao.getPages(docId).size,
                    filePath = renderedTarget.absolutePath, width = draftPage.width, height = draftPage.height,
                    sourcePath = sourceTarget.absolutePath, quarterTurns = draftPage.recipe.quarterTurns,
                    cropPoints = draftPage.recipe.encodeCorners(), filter = draftPage.recipe.filter.name,
                    brightness = draftPage.recipe.brightness, contrast = draftPage.recipe.contrast,
                    fineRotation = draftPage.recipe.fineRotation, cropRatio = draftPage.recipe.cropRatio,
                    updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0L) + 1))))
                rebuildDocumentOcr(docId)
                refreshMeta(docId)
            }; committed = true }
            withContext(NonCancellable) {
                existing?.let { File(it.sourcePath).takeIf { f -> f.isFile }?.delete(); File(it.filePath).delete() }
                refreshMeta(docId)
            }
        } finally { if (!committed) { sourceTarget.delete(); renderedTarget.delete() } }
    }

    /** 兼容旧工具：只有处理图时也保存为原图，后续仍可重新裁边。 */
    suspend fun appendProcessedPage(docId: String, source: File) = withContext(Dispatchers.IO) {
        val bmp = ImageIo.loadFromFile(source, 3200) ?: return@withContext
        val temporarySource = File(FileStore.draftWorkDir(app), "legacy_source_${System.nanoTime()}.jpg")
        val temporaryRendered = File(FileStore.draftWorkDir(app), "legacy_rendered_${System.nanoTime()}.jpg")
        ImageIo.saveJpeg(bmp, temporarySource, 94)
        ImageIo.saveJpeg(bmp, temporaryRendered, 94)
        val page = DraftPage(
            id = "temporary_${UUID.randomUUID().toString().take(8)}",
            sourcePath = temporarySource.absolutePath,
            renderedPath = temporaryRendered.absolutePath,
            width = bmp.width,
            height = bmp.height,
            recipe = EditRecipe(filter = ScanFilter.ORIGINAL)
        )
        bmp.recycle()
        appendPage(docId, page)
        temporarySource.delete()
        temporaryRendered.delete()
    }

    suspend fun pages(docId: String): List<PageEntity> = withContext(Dispatchers.IO) { dao.getPages(docId) }

    suspend fun updatePage(pageId: String, result: EditResult) = withContext(Dispatchers.IO) {
        val page = dao.getPage(pageId) ?: return@withContext
        val version = UUID.randomUUID().toString()
        val sourceTarget = File(FileStore.docDir(app, page.docId), "source_${page.id}_$version.jpg")
        val renderedTarget = File(FileStore.docDir(app, page.docId), "page_${page.id}_$version.jpg")
        var committed = false
        try {
            AtomicFiles.copy(result.sourceFile, sourceTarget)
            AtomicFiles.copy(result.renderedFile, renderedTarget)
            withContext(NonCancellable) { database.withTransaction {
                check(dao.getPage(pageId)?.updatedAt == page.updatedAt) { "页面已变化，请重新打开编辑" }
                dao.updatePage(page.copy(sourcePath = sourceTarget.absolutePath, filePath = renderedTarget.absolutePath,
                    width = result.width, height = result.height, quarterTurns = result.recipe.quarterTurns,
                    cropPoints = result.recipe.encodeCorners(), filter = result.recipe.filter.name,
                    brightness = result.recipe.brightness, contrast = result.recipe.contrast,
                    fineRotation = result.recipe.fineRotation, cropRatio = result.recipe.cropRatio, ocrText = "", ocrLayout = "", ocrMode = "", ocrUpdatedAt = 0L,
                    updatedAt = maxOf(System.currentTimeMillis(), page.updatedAt + 1)))
                rebuildDocumentOcr(page.docId)
                refreshMeta(page.docId)
            }; committed = true }
            withContext(NonCancellable) {
                File(page.sourcePath).takeIf { it.isFile }?.delete(); File(page.filePath).delete()
                refreshMeta(page.docId)
            }
        } finally { if (!committed) { sourceTarget.delete(); renderedTarget.delete() } }
    }

    suspend fun trashPage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(dao.getPage(pageId)?.docId == docId) { "页面不存在" }
            dao.trashPage(pageId, System.currentTimeMillis())
            compactIndices(docId); rebuildDocumentOcr(docId); refreshMeta(docId)
        }
        syncSearch(docId)
    }

    suspend fun restorePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val page = dao.getPage(pageId) ?: return@withTransaction
            require(page.docId == docId) { "页面不属于此文档" }
            if (page.deleted) dao.updatePage(page.copy(deleted = false, pageIndex = dao.getPages(docId).size, updatedAt = maxOf(System.currentTimeMillis(), page.updatedAt + 1)))
            compactIndices(docId); rebuildDocumentOcr(docId); refreshMeta(docId)
        }
        syncSearch(docId)
    }

    suspend fun duplicatePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        val source = dao.getPage(pageId) ?: error("页面不存在")
        require(source.docId == docId && !source.deleted) { "页面已移除" }
        val newId = uniquePageId(docId)
        val sourceTarget = FileStore.sourceFile(app, docId, newId)
        val renderedTarget = FileStore.pageFile(app, docId, newId)
        var committed = false
        try {
            AtomicFiles.copy(File(source.sourcePath.ifBlank { source.filePath }), sourceTarget)
            AtomicFiles.copy(File(source.filePath), renderedTarget)
            withContext(NonCancellable) {
                database.withTransaction {
                    check(dao.getPage(pageId)?.updatedAt == source.updatedAt && dao.getPage(pageId)?.deleted == false) { "页面已变化，请重新复制" }
                    val all = dao.getPages(docId).toMutableList()
                    val index = all.indexOfFirst { it.id == pageId } + 1
                    require(index > 0 && dao.getDoc(docId)?.deleted == false) { "文档或页面已移除" }
                    val copy = source.copy(id = newId, pageIndex = index, sourcePath = sourceTarget.absolutePath,
                        filePath = renderedTarget.absolutePath, updatedAt = System.currentTimeMillis())
                    dao.insertPages(listOf(copy)); all.add(index, copy)
                    all.forEachIndexed { position, page -> dao.updatePage(page.copy(pageIndex = position)) }
                    rebuildDocumentOcr(docId); refreshMeta(docId)
                }
                committed = true
                syncSearch(docId)
            }
        } finally { if (!committed) { sourceTarget.delete(); renderedTarget.delete() } }
    }

    suspend fun movePage(docId: String, from: Int, to: Int) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val pages = dao.getPages(docId).toMutableList()
            if (from !in pages.indices || to !in pages.indices || from == to) return@withTransaction
            pages.add(to, pages.removeAt(from))
            pages.forEachIndexed { index, page -> dao.updatePage(page.copy(pageIndex = index)) }
            rebuildDocumentOcr(docId)
            refreshMeta(docId)
        }
        syncSearch(docId)
    }

    suspend fun applyEnhancementToAll(docId: String, filter: ScanFilter, brightness: Float, contrast: Float): BatchEnhanceResult = withContext(Dispatchers.IO) {
        val pages = dao.getPages(docId)
        val failures = mutableListOf<Int>(); var succeeded = 0
        pages.forEachIndexed { index, page ->
            val temporary = File(FileStore.draftWorkDir(app), "batch_${UUID.randomUUID()}.jpg")
            val ok = try {
                val original = ImageIo.loadFromFile(File(page.sourcePath.ifBlank { page.filePath }), 4000) ?: error("无法读取原图")
                var rotated: android.graphics.Bitmap? = null; var rendered: android.graphics.Bitmap? = null
                val recipe = EditRecipe(page.quarterTurns, EditRecipe.decodeCorners(page.cropPoints), filter, brightness, contrast, page.fineRotation, page.cropRatio)
                try {
                    rotated = ImageIo.rotate(original, page.quarterTurns * 90f)
                    rendered = renderProcessed(rotated, recipe.corners, filter, brightness, contrast, 3200, page.fineRotation, page.cropRatio)
                    check(ImageIo.saveJpeg(rendered, temporary, 94)) { "无法生成页面" }
                    updatePage(page.id, EditResult(File(page.sourcePath.ifBlank { page.filePath }), temporary, recipe, rendered.width, rendered.height))
                } finally {
                    if (rendered !== rotated) rendered?.recycle()
                    if (rotated !== original) rotated?.recycle()
                    original.recycle()
                }
                true
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; false }
            finally { temporary.delete() }
            if (ok) succeeded++ else failures += index + 1
        }
        refreshMeta(docId)
        BatchEnhanceResult(pages.size, succeeded, failures)
    }

    suspend fun rename(docId: String, title: String) = withContext(Dispatchers.IO) {
        dao.renameDoc(docId, title, System.currentTimeMillis())
        syncSearch(docId)
    }

    suspend fun setOrganization(docId: String, folder: String?, tags: String) = withContext(Dispatchers.IO) {
        val normalizedTags = tags.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        dao.organizeDoc(docId, folder?.trim()?.takeIf { it.isNotBlank() }, normalizedTags, System.currentTimeMillis())
        syncSearch(docId)
    }

    suspend fun setOcrText(docId: String, text: String) = withContext(Dispatchers.IO) {
        dao.setDocCorrection(docId, text, System.currentTimeMillis())
        syncSearch(docId)
    }

    suspend fun rebuildDocumentOcr(docId: String): String = withContext(Dispatchers.IO) {
        val combined = dao.getPages(docId).mapIndexedNotNull { index, page ->
            page.ocrText.trim().takeIf { it.isNotBlank() }?.let { "【第 ${index + 1} 页】\n$it" }
        }.joinToString("\n\n")
        dao.setDocOcr(docId, combined, System.currentTimeMillis())
        if (!database.inTransaction()) syncSearch(docId)
        combined
    }

    suspend fun trash(docId: String) = withContext(Dispatchers.IO) { dao.moveToTrash(docId); syncSearch(docId) }
    suspend fun restoreDoc(docId: String) = withContext(Dispatchers.IO) { dao.restoreDoc(docId, System.currentTimeMillis()); syncSearch(docId) }

    suspend fun deleteForever(docId: String) = withContext(Dispatchers.IO) {
        database.withTransaction { dao.deletePages(docId); dao.deleteForever(docId) }
        syncSearch(docId)
        FileStore.docDir(app, docId).deleteRecursively()
    }

    suspend fun exportPdf(docId: String, output: File, pageSize: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        withContext(Dispatchers.IO) {
            output.parentFile?.mkdirs()
            runCatching {
                checkedPages(docId)
                AtomicFiles.write(output) { file -> check(file.outputStream().use { stream -> exportPdfToStream(docId, stream, pageSize, maxImageSide) }) }
                true
            }.getOrDefault(false)
        }

    suspend fun exportPdf(docId: String, uri: Uri, pageSize: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                checkedPages(docId)
                checkedPages(docId)
            app.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    exportPdfToStream(docId, stream, pageSize, maxImageSide)
                } ?: false
            }.getOrDefault(false)
        }

    suspend fun exportSearchablePdf(docId: String, output: File, pageSize: PdfExporter.PageSize = PdfExporter.PageSize.A4, maxImageSide: Int = 3200): Boolean = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        runCatching {
            checkedPages(docId)
            AtomicFiles.write(output) { file -> check(file.outputStream().buffered().use { stream -> exportSearchablePdfToStream(docId, stream, pageSize, maxImageSide) }) }
            true
        }.getOrDefault(false)
    }

    suspend fun exportSearchablePdf(docId: String, uri: Uri, pageSize: PdfExporter.PageSize = PdfExporter.PageSize.A4, maxImageSide: Int = 3200): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            checkedPages(docId)
            app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { stream ->
                exportSearchablePdfToStream(docId, stream, pageSize, maxImageSide)
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun exportJpegs(docId: String, treeUri: Uri, baseName: String): Int =
        withContext(Dispatchers.IO) {
            val directory = DocumentFile.fromTreeUri(app, treeUri) ?: return@withContext 0
            var count = 0
            val pages = dao.getPages(docId)
            var attempt = 1
            var prefix = baseName
            fun proposed(index: Int): String = if (pages.size == 1) "$prefix.jpg" else "${prefix}_${(index + 1).toString().padStart(2, '0')}.jpg"
            while (pages.indices.any { directory.findFile(proposed(it)) != null }) {
                attempt++
                prefix = "$baseName ($attempt)"
            }
            pages.forEachIndexed { index, page ->
                val name = proposed(index)
                val target = directory.createFile("image/jpeg", name) ?: return@forEachIndexed
                val copied = runCatching {
                    app.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                        File(page.filePath).inputStream().use { input -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
                if (copied) count++
            }
            count
        }

    suspend fun renderedFiles(docId: String): List<File> = withContext(Dispatchers.IO) {
        dao.getPages(docId).map { File(it.filePath) }.filter { it.exists() }
    }

    suspend fun exportLongImage(docId: String, output: File): Boolean = withContext(Dispatchers.IO) {
        val files = dao.getPages(docId).map { File(it.filePath) }
        val stitched = Stitch.verticalFiles(files)
        try { ImageIo.saveJpeg(stitched, output, 90) } finally { stitched.recycle() }
    }

    suspend fun backupLibrary(uri: Uri, password: CharArray? = null): LibraryTransferResult = withContext(Dispatchers.IO) {
        runCatching {
            val docs = dao.getAllDocs()
            var pageCount = 0
            app.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                val output = if (password == null) raw else com.localdoc.scanner.security.EncryptedBackup.output(raw, password)
                ZipOutputStream(output.buffered()).use { zip ->
                    val root = JSONObject().put("format", 1).put("exportedAt", System.currentTimeMillis())
                    val docsJson = JSONArray()
                    val files = mutableListOf<Pair<String, File>>()
                    docs.forEachIndexed { docIndex, doc ->
                        val pages = dao.getAllPages(doc.id)
                        pageCount += pages.size
                        val pagesJson = JSONArray()
                        pages.forEachIndexed { pageIndex, page ->
                            val base = "files/d${docIndex}/p${pageIndex}"
                            val source = File(page.sourcePath.ifBlank { page.filePath })
                            val rendered = File(page.filePath)
                            require(source.isFile && rendered.isFile) { "${doc.title}第${pageIndex + 1}页原图或处理图缺失，未生成完整备份" }
                            val sourceEntry = "$base-source.jpg"
                            val renderedEntry = "$base-rendered.jpg"
                            if (source.exists()) files += sourceEntry to source
                            if (rendered.exists()) files += renderedEntry to rendered
                            pagesJson.put(
                                JSONObject()
                                    .put("pageIndex", page.pageIndex)
                                    .put("width", page.width)
                                    .put("height", page.height)
                                    .put("quarterTurns", page.quarterTurns)
                                    .put("cropPoints", page.cropPoints)
                                    .put("filter", page.filter)
                                    .put("brightness", page.brightness.toDouble())
                                    .put("contrast", page.contrast.toDouble())
                                    .put("fineRotation", page.fineRotation.toDouble())
                                    .put("cropRatio", page.cropRatio.toDouble())
                                    .put("deleted", page.deleted)
                                    .put("ocrText", page.ocrText)
                                    .put("ocrLayout", page.ocrLayout)
                                    .put("ocrMode", page.ocrMode)
                                    .put("ocrUpdatedAt", page.ocrUpdatedAt)
                                    .put("sourceEntry", sourceEntry.takeIf { source.exists() }.orEmpty())
                                    .put("renderedEntry", renderedEntry.takeIf { rendered.exists() }.orEmpty())
                            )
                        }
                        docsJson.put(
                            JSONObject()
                                .put("title", doc.title)
                                .put("createdAt", doc.createdAt)
                                .put("folder", doc.folder ?: JSONObject.NULL)
                                .put("tags", doc.tags)
                                .put("ocrText", doc.ocrText)
                        .put("ocrCorrection", doc.ocrCorrection)
                                .put("ocrLegacyText", doc.ocrLegacyText)
                                .put("favorite", doc.favorite)
                                .put("locked", doc.locked)
                                .put("deleted", doc.deleted)
                                .put("pages", pagesJson)
                        )
                    }
                    root.put("docs", docsJson)
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(root.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    files.forEach { (name, file) ->
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法创建备份文件")
            LibraryTransferResult(docs.size, pageCount, true)
        }.getOrElse { LibraryTransferResult(0, 0, false, it.message ?: "备份失败") }
    }

    suspend fun restoreEncryptedLibrary(uri: Uri, password: CharArray): LibraryTransferResult = withContext(Dispatchers.IO) {
        val verified = File(app.cacheDir, "secure-restore-${UUID.randomUUID()}.zip")
        try {
            app.contentResolver.openInputStream(uri)?.use {
                com.localdoc.scanner.security.EncryptedBackup.decrypt(it, verified, password)
            } ?: error("无法读取加密备份")
            restoreLibrary(Uri.fromFile(verified))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LibraryTransferResult(0, 0, false, e.message ?: "加密备份恢复失败")
        } finally { verified.delete() }
    }

    suspend fun restoreLibrary(uri: Uri): LibraryTransferResult = withContext(Dispatchers.IO) {
        val restoreContext = kotlinx.coroutines.currentCoroutineContext()
        val temporary = File(app.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        val createdDocIds = mutableListOf<String>()
        val preparedDocs = mutableListOf<DocEntity>()
        val preparedPages = mutableMapOf<String, List<PageEntity>>()
        var committed = false
        runCatching {
            var manifest: String? = null
            val entries = mutableSetOf<String>()
            var extractedBytes = 0L
            app.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        require(!entry.isDirectory && (name == "manifest.json" || name.matches(Regex("files/d\\d+/p\\d+-(source|rendered)\\.jpg"))) && entries.add(name) && entries.size <= 50000) {
                            "备份包含无效路径"
                        }
                        fun copyBounded(target: OutputStream, limit: Long) {
                            val buffer = ByteArray(64 * 1024); var entryBytes = 0L
                            while (true) {
                                restoreContext.ensureActive()
                                val count = zip.read(buffer); if (count < 0) break
                                entryBytes += count; extractedBytes += count
                                require(entryBytes <= limit && extractedBytes <= 8L * 1024 * 1024 * 1024) { "备份内容超过恢复大小限制" }
                                target.write(buffer, 0, count)
                            }
                        }
                        if (name == "manifest.json") {
                            manifest = java.io.ByteArrayOutputStream().apply { copyBounded(this, 64L * 1024 * 1024) }.toString("UTF-8")
                        } else {
                            val target = File(temporary, name)
                            require(target.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) { "备份路径越界" }
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { copyBounded(it, 8L * 1024 * 1024 * 1024) }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法读取备份文件")

            val root = JSONObject(manifest ?: error("备份缺少清单"))
            require(root.optInt("format") == 1) { "不支持的备份版本" }
            val docsJson = root.getJSONArray("docs")
            val existingTitles = dao.getAllDocs().map { it.title }.toMutableSet()
            var restoredPages = 0
            for (docIndex in 0 until docsJson.length()) {
                val item = docsJson.getJSONObject(docIndex)
                val now = System.currentTimeMillis()
                val id = "d${now.toString(36)}${UUID.randomUUID().toString().take(6)}"
                val title = item.optString("title", "恢复的文档")
                createdDocIds += id
                preparedDocs += (
                    DocEntity(
                        id = id,
                        title = if (title in existingTitles) "$title（恢复）" else title,
                        createdAt = item.optLong("createdAt", now),
                        updatedAt = now,
                        pageCount = 0,
                        sizeBytes = 0,
                        folder = item.optString("folder").takeIf { it.isNotBlank() && it != "null" },
                        tags = item.optString("tags"),
                        ocrText = item.optString("ocrText"),
                        ocrCorrection = item.optString("ocrCorrection"),
                        ocrLegacyText = item.optString("ocrLegacyText", item.optString("ocrText")),
                        favorite = item.optBoolean("favorite"),
                        locked = item.optBoolean("locked"),
                        deleted = item.optBoolean("deleted")
                    )
                )
                existingTitles += preparedDocs.last().title
                val pagesJson = item.getJSONArray("pages")
                val restored = mutableListOf<PageEntity>()
                for (pageIndex in 0 until pagesJson.length()) {
                    val page = pagesJson.getJSONObject(pageIndex)
                    val pageId = "${id}_p${pageIndex}_${UUID.randomUUID().toString().take(5)}"
                    require(page.optString("renderedEntry") in entries && page.optString("sourceEntry").let { it.isBlank() || it in entries }) { "备份清单引用了不存在的文件" }
                    val source = File(temporary, page.optString("sourceEntry"))
                    val rendered = File(temporary, page.optString("renderedEntry"))
                    require(rendered.isFile) { "备份缺少第 ${pageIndex + 1} 页文件" }
                    val sourceTarget = FileStore.sourceFile(app, id, pageId)
                    val renderedTarget = FileStore.pageFile(app, id, pageId)
                    (source.takeIf { it.isFile } ?: rendered).copyTo(sourceTarget, overwrite = true)
                    rendered.copyTo(renderedTarget, overwrite = true)
                    restored += PageEntity(
                        id = pageId,
                        docId = id,
                        pageIndex = restored.size,
                        filePath = renderedTarget.absolutePath,
                        width = page.optInt("width"),
                        height = page.optInt("height"),
                        sourcePath = sourceTarget.absolutePath,
                        quarterTurns = page.optInt("quarterTurns"),
                        cropPoints = page.optString("cropPoints"),
                        filter = page.optString("filter", "AUTO"),
                        brightness = page.optDouble("brightness", 0.0).toFloat(),
                        contrast = page.optDouble("contrast", 1.0).toFloat(),
                        fineRotation = page.optDouble("fineRotation", 0.0).toFloat(),
                        cropRatio = page.optDouble("cropRatio", 0.0).toFloat(),
                        deleted = page.optBoolean("deleted"),
                        updatedAt = now,
                        ocrText = page.optString("ocrText"),
                        ocrLayout = page.optString("ocrLayout"),
                        ocrMode = page.optString("ocrMode"),
                        ocrUpdatedAt = page.optLong("ocrUpdatedAt")
                    )
                }
                preparedPages[id] = restored
                restoredPages += restored.size
            }
            restoreContext.ensureActive()
            withContext(NonCancellable) {
                database.withTransaction {
                    preparedDocs.forEach { doc -> dao.insertDoc(doc); dao.insertPages(preparedPages.getValue(doc.id)); refreshMeta(doc.id) }
                }
                committed = true
                preparedDocs.forEach { syncSearch(it.id) }
            }
            LibraryTransferResult(docsJson.length(), restoredPages, true)
        }.getOrElse { failure ->
            withContext(NonCancellable) { createdDocIds.forEach { docId ->
                runCatching {
                    if (!committed) {
                        database.withTransaction { dao.deletePages(docId); dao.deleteForever(docId) }
                        FileStore.docDir(app, docId).deleteRecursively()
                    }
                }
            }
            }
            temporary.deleteRecursively()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            LibraryTransferResult(0, 0, false, failure.message ?: "恢复失败")
        }
            .also { temporary.deleteRecursively() }
    }

    private suspend fun compactIndices(docId: String) {
        dao.getPages(docId).forEachIndexed { index, page ->
            if (page.pageIndex != index) dao.updatePage(page.copy(pageIndex = index))
        }
    }

    private suspend fun exportPdfToStream(
        docId: String,
        output: OutputStream,
        pageSize: PdfExporter.PageSize,
        maxImageSide: Int
    ): Boolean {
        val files = checkedPages(docId).map { File(it.filePath) }
        return PdfExporter.exportFiles(files, output, pageSize, maxImageSide)
    }

    private suspend fun exportSearchablePdfToStream(docId: String, output: OutputStream, pageSize: PdfExporter.PageSize, maxImageSide: Int): Boolean {
        val pages = checkedPages(docId).map { page ->
            SearchablePdfPage(File(page.filePath), page.ocrText, com.localdoc.scanner.ocr.OcrLayout.decode(page.ocrLayout))
        }
        return SearchablePdfExporter.export(app, pages, output, pageSize, maxImageSide)
    }

    suspend fun exportProblem(docId: String): String? = withContext(Dispatchers.IO) {
        runCatching { checkedPages(docId) }.exceptionOrNull()?.message
    }

    private suspend fun checkedPages(docId: String): List<PageEntity> {
        val pages = dao.getPages(docId)
        require(pages.isNotEmpty()) { "文档没有页面" }
        val missing = pages.mapIndexedNotNull { i, p -> if (!File(p.filePath).isFile) i + 1 else null }
        require(missing.isEmpty()) { "第 ${missing.joinToString()} 页文件缺失，请修复后导出" }
        return pages
    }

    private suspend fun syncSearch(docId: String) {
        try {
            FullTextIndex(app).use { index ->
                val doc = dao.getDoc(docId)
                if (doc == null || doc.deleted) index.removeScan(docId) else index.indexScan(doc.toItem())
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("LocalDocScanner", "检索索引更新失败，可从全文检索重新建立索引", e)
        }
    }

    /** Correct one line without discarding the coordinates of other lines. */
    suspend fun correctOcrLine(pageId: String, line: Int, text: String, expectedVersion: Long) = withContext(Dispatchers.IO) {
        var docId = ""
        database.withTransaction {
            val page = dao.getPage(pageId) ?: error("页面不存在")
            check(page.updatedAt == expectedVersion && !page.deleted) { "页面或识别结果已变化，请重新打开校正" }
            val boxes = com.localdoc.scanner.ocr.OcrLayout.decode(page.ocrLayout).toMutableList()
            require(line in boxes.indices) { "文字位置不存在，请重新识别" }
            boxes[line] = boxes[line].copy(text = text)
            val now = maxOf(System.currentTimeMillis(), page.updatedAt + 1)
            dao.updatePage(page.copy(ocrText = boxes.joinToString("\n") { it.text },
                ocrLayout = com.localdoc.scanner.ocr.OcrLayout.encode(boxes), ocrUpdatedAt = now, updatedAt = now))
            rebuildDocumentOcr(page.docId)
            docId = page.docId
        }
        syncSearch(docId)
    }

    private suspend fun refreshMeta(docId: String) {
        if (dao.getDoc(docId) == null) return
        val pages = dao.getPages(docId)
        val size = FileStore.docDir(app, docId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dao.setDocMeta(docId, pages.size, size, pages.firstOrNull()?.filePath.orEmpty(), System.currentTimeMillis())
        if (!database.inTransaction()) syncSearch(docId)
    }

    private fun uniquePageId(docId: String): String =
        "${docId}_p${System.currentTimeMillis().toString(36)}${UUID.randomUUID().toString().take(5)}"

    private fun DocEntity.toItem(): DocItem = DocItem(
        id = id,
        title = title,
        pageCount = pageCount,
        updatedAt = updatedAt,
        sizeBytes = sizeBytes,
        coverPath = coverPath.takeIf { it.isNotBlank() && File(it).exists() }
            ?: FileStore.legacyPageFile(app, id, 0).takeIf { it.exists() }?.absolutePath,
        folder = folder,
        tags = tags,
        ocrText = ocrCorrection.ifBlank { ocrText },
        createdAt = createdAt,
        legacyOcrText = ocrLegacyText, favorite = favorite
    )
}

data class BatchEnhanceResult(
    val pageCount: Int,
    val succeededPages: Int,
    val failedPages: List<Int>
)

data class LibraryTransferResult(
    val documentCount: Int,
    val pageCount: Int,
    val success: Boolean,
    val error: String = ""
)
