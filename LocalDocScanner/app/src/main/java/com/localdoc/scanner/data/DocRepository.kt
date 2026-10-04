package com.localdoc.scanner.data

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
    private val dao = AppDatabase.get(app).docDao()

    fun observeDocs(): Flow<List<DocItem>> = dao.observeDocs().map { list -> list.map { it.toItem() } }
    fun observeTrashed(): Flow<List<DocItem>> = dao.observeTrashed().map { list -> list.map { it.toItem() } }
    suspend fun search(q: String): List<DocItem> = dao.search(q).map { it.toItem() }

    suspend fun createDoc(title: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = "d${now.toString(36)}${(100..999).random()}"
        dao.insertDoc(DocEntity(id, title, now, now, 0, 0))
        FileStore.docDir(app, id)
        id
    }

    suspend fun appendPage(docId: String, draftPage: DraftPage) = withContext(Dispatchers.IO) {
        val doc = dao.getDoc(docId) ?: return@withContext
        val pageId = "${docId}_${draftPage.id}"
        val existing = dao.getPage(pageId)
        val sourceTarget = FileStore.sourceFile(app, docId, pageId)
        val renderedTarget = FileStore.pageFile(app, docId, pageId)
        File(draftPage.sourcePath).copyTo(sourceTarget, overwrite = true)
        File(draftPage.renderedPath).copyTo(renderedTarget, overwrite = true)
        dao.insertPages(
            listOf(
                PageEntity(
                    id = pageId,
                    docId = docId,
                    pageIndex = existing?.pageIndex ?: doc.pageCount,
                    filePath = renderedTarget.absolutePath,
                    width = draftPage.width,
                    height = draftPage.height,
                    sourcePath = sourceTarget.absolutePath,
                    quarterTurns = draftPage.recipe.quarterTurns,
                    cropPoints = draftPage.recipe.encodeCorners(),
                    filter = draftPage.recipe.filter.name,
                    brightness = draftPage.recipe.brightness,
                    contrast = draftPage.recipe.contrast,
                    fineRotation = draftPage.recipe.fineRotation,
                    updatedAt = System.currentTimeMillis()
                )
            )
        )
        refreshMeta(docId)
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
        val sourceTarget = FileStore.sourceFile(app, page.docId, page.id)
        val renderedTarget = FileStore.pageFile(app, page.docId, page.id)
        if (result.sourceFile.absolutePath != sourceTarget.absolutePath) result.sourceFile.copyTo(sourceTarget, overwrite = true)
        if (result.renderedFile.absolutePath != renderedTarget.absolutePath) result.renderedFile.copyTo(renderedTarget, overwrite = true)
        dao.updatePage(
            page.copy(
                sourcePath = sourceTarget.absolutePath,
                filePath = renderedTarget.absolutePath,
                width = result.width,
                height = result.height,
                quarterTurns = result.recipe.quarterTurns,
                cropPoints = result.recipe.encodeCorners(),
                filter = result.recipe.filter.name,
                brightness = result.recipe.brightness,
                contrast = result.recipe.contrast,
                fineRotation = result.recipe.fineRotation,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshMeta(page.docId)
    }

    suspend fun trashPage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        dao.trashPage(pageId, System.currentTimeMillis())
        compactIndices(docId)
        refreshMeta(docId)
    }

    suspend fun restorePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        val page = dao.getPage(pageId) ?: return@withContext
        dao.updatePage(
            page.copy(
                deleted = false,
                pageIndex = dao.getPages(docId).size,
                updatedAt = System.currentTimeMillis()
            )
        )
        compactIndices(docId)
        refreshMeta(docId)
    }

    suspend fun duplicatePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        val source = dao.getPage(pageId) ?: return@withContext
        val all = dao.getPages(docId).toMutableList()
        val insertIndex = (all.indexOfFirst { it.id == pageId } + 1).coerceAtLeast(0)
        val newId = uniquePageId(docId)
        val sourceTarget = FileStore.sourceFile(app, docId, newId)
        val renderedTarget = FileStore.pageFile(app, docId, newId)
        File(source.sourcePath.ifBlank { source.filePath }).copyTo(sourceTarget, overwrite = true)
        File(source.filePath).copyTo(renderedTarget, overwrite = true)
        all.add(
            insertIndex,
            source.copy(
                id = newId,
                pageIndex = insertIndex,
                sourcePath = sourceTarget.absolutePath,
                filePath = renderedTarget.absolutePath,
                updatedAt = System.currentTimeMillis()
            )
        )
        all.forEachIndexed { index, page -> dao.updatePage(page.copy(pageIndex = index)) }
        refreshMeta(docId)
    }

    suspend fun movePage(docId: String, from: Int, to: Int) = withContext(Dispatchers.IO) {
        val pages = dao.getPages(docId).toMutableList()
        if (from !in pages.indices || to !in pages.indices || from == to) return@withContext
        pages.add(to, pages.removeAt(from))
        pages.forEachIndexed { index, page -> dao.updatePage(page.copy(pageIndex = index)) }
        refreshMeta(docId)
    }

    suspend fun applyEnhancementToAll(
        docId: String,
        filter: ScanFilter,
        brightness: Float,
        contrast: Float
    ): BatchEnhanceResult = withContext(Dispatchers.IO) {
        val pages = dao.getPages(docId)
        val failures = mutableListOf<Int>()
        var succeeded = 0
        pages.forEachIndexed { index, page ->
            val ok = runCatching {
                val original = ImageIo.loadFromFile(File(page.sourcePath.ifBlank { page.filePath }), 4000)
                    ?: error("无法读取原图")
                val rotated = ImageIo.rotate(original, page.quarterTurns * 90f)
                val rendered = renderProcessed(
                    rotated = rotated,
                    corners = EditRecipe.decodeCorners(page.cropPoints),
                    filter = filter,
                    brightness = brightness,
                    contrast = contrast,
                    maxSide = 3200,
                    fineRotation = page.fineRotation
                )
                val target = File(page.filePath)
                val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp.jpg")
                val saved = ImageIo.saveJpeg(rendered, temporary, 94)
                if (rendered !== rotated) rendered.recycle()
                if (rotated !== original) rotated.recycle()
                original.recycle()
                check(saved) { "无法生成页面" }
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
                dao.updatePage(
                    page.copy(
                        filter = filter.name,
                        brightness = brightness,
                        contrast = contrast,
                        ocrText = "",
                        ocrLayout = "",
                        ocrMode = "",
                        ocrUpdatedAt = 0L,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.isSuccess
            if (ok) succeeded++ else failures += index + 1
        }
        rebuildDocumentOcr(docId)
        refreshMeta(docId)
        BatchEnhanceResult(pages.size, succeeded, failures)
    }

    suspend fun rename(docId: String, title: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDoc(docId) ?: return@withContext
        dao.updateDoc(doc.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setOrganization(docId: String, folder: String?, tags: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDoc(docId) ?: return@withContext
        val normalizedTags = tags.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        dao.updateDoc(
            doc.copy(
                folder = folder?.trim()?.takeIf { it.isNotBlank() },
                tags = normalizedTags,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setOcrText(docId: String, text: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDoc(docId) ?: return@withContext
        dao.updateDoc(doc.copy(ocrText = text, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setPageOcr(pageId: String, text: String, mode: String, boxes: List<com.localdoc.scanner.ocr.OcrTextBox> = emptyList()) = withContext(Dispatchers.IO) {
        val page = dao.getPage(pageId) ?: return@withContext
        dao.updatePage(
            page.copy(
                ocrText = text,
                ocrLayout = com.localdoc.scanner.ocr.OcrLayout.encode(boxes),
                ocrMode = mode,
                ocrUpdatedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun rebuildDocumentOcr(docId: String): String = withContext(Dispatchers.IO) {
        val combined = dao.getPages(docId).mapIndexedNotNull { index, page ->
            page.ocrText.trim().takeIf { it.isNotBlank() }?.let { "【第 ${index + 1} 页】\n$it" }
        }.joinToString("\n\n")
        val doc = dao.getDoc(docId)
        if (doc != null) dao.updateDoc(doc.copy(ocrText = combined, updatedAt = System.currentTimeMillis()))
        combined
    }

    suspend fun trash(docId: String) = withContext(Dispatchers.IO) { dao.moveToTrash(docId) }
    suspend fun restoreDoc(docId: String) = withContext(Dispatchers.IO) { dao.restoreDoc(docId, System.currentTimeMillis()) }

    suspend fun deleteForever(docId: String) = withContext(Dispatchers.IO) {
        dao.deletePages(docId)
        dao.deleteForever(docId)
        FileStore.docDir(app, docId).deleteRecursively()
    }

    suspend fun exportPdf(docId: String, output: File, pageSize: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        withContext(Dispatchers.IO) {
            output.parentFile?.mkdirs()
            runCatching {
                output.outputStream().use { stream -> exportPdfToStream(docId, stream, pageSize, maxImageSide) }
            }.getOrDefault(false)
        }

    suspend fun exportPdf(docId: String, uri: Uri, pageSize: PdfExporter.PageSize, maxImageSide: Int = 3200): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    exportPdfToStream(docId, stream, pageSize, maxImageSide)
                } ?: false
            }.getOrDefault(false)
        }

    suspend fun exportSearchablePdf(docId: String, output: File): Boolean = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        runCatching {
            output.outputStream().buffered().use { stream -> exportSearchablePdfToStream(docId, stream) }
        }.getOrDefault(false)
    }

    suspend fun exportSearchablePdf(docId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { stream ->
                exportSearchablePdfToStream(docId, stream)
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
        val temporary = File(app.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        val createdDocIds = mutableListOf<String>()
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
            var restoredPages = 0
            for (docIndex in 0 until docsJson.length()) {
                val item = docsJson.getJSONObject(docIndex)
                val now = System.currentTimeMillis()
                val id = "d${now.toString(36)}${UUID.randomUUID().toString().take(6)}"
                val title = item.optString("title", "恢复的文档")
                dao.insertDoc(
                    DocEntity(
                        id = id,
                        title = if (dao.getAllDocs().any { it.title == title }) "$title（恢复）" else title,
                        createdAt = item.optLong("createdAt", now),
                        updatedAt = now,
                        pageCount = 0,
                        sizeBytes = 0,
                        folder = item.optString("folder").takeIf { it.isNotBlank() && it != "null" },
                        tags = item.optString("tags"),
                        ocrText = item.optString("ocrText"),
                        locked = item.optBoolean("locked"),
                        deleted = item.optBoolean("deleted")
                    )
                )
                createdDocIds += id
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
                        deleted = page.optBoolean("deleted"),
                        updatedAt = now,
                        ocrText = page.optString("ocrText"),
                        ocrLayout = page.optString("ocrLayout"),
                        ocrMode = page.optString("ocrMode"),
                        ocrUpdatedAt = page.optLong("ocrUpdatedAt")
                    )
                }
                dao.insertPages(restored)
                restoredPages += restored.size
                refreshMeta(id)
            }
            LibraryTransferResult(docsJson.length(), restoredPages, true)
        }.getOrElse { failure ->
            createdDocIds.forEach { docId ->
                runCatching {
                    dao.deletePages(docId)
                    dao.deleteForever(docId)
                    FileStore.docDir(app, docId).deleteRecursively()
                }
            }
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
        val files = dao.getPages(docId).map { File(it.filePath) }.filter { it.exists() }
        return PdfExporter.exportFiles(files, output, pageSize, maxImageSide)
    }

    private suspend fun exportSearchablePdfToStream(docId: String, output: OutputStream): Boolean {
        val pages = dao.getPages(docId).mapNotNull { page ->
            File(page.filePath).takeIf(File::exists)?.let { SearchablePdfPage(it, page.ocrText, com.localdoc.scanner.ocr.OcrLayout.decode(page.ocrLayout)) }
        }
        return SearchablePdfExporter.export(app, pages, output)
    }

    private suspend fun refreshMeta(docId: String) {
        val doc = dao.getDoc(docId) ?: return
        val pages = dao.getPages(docId)
        val size = FileStore.docDir(app, docId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dao.updateDoc(
            doc.copy(
                pageCount = pages.size,
                updatedAt = System.currentTimeMillis(),
                sizeBytes = size,
                coverPath = pages.firstOrNull()?.filePath.orEmpty()
            )
        )
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
        ocrText = ocrText,
        createdAt = createdAt
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
