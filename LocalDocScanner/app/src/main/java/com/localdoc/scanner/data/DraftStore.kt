package com.localdoc.scanner.data

import android.content.Context
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.edit.EditRecipe
import com.localdoc.scanner.edit.EditResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DraftPage(
    val id: String,
    val sourcePath: String,
    val renderedPath: String,
    val width: Int,
    val height: Int,
    val recipe: EditRecipe,
    val createdAt: Long = System.currentTimeMillis()
)

data class ScanDraft(
    val title: String = "",
    val appendDocId: String? = null,
    val pages: List<DraftPage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 扫描中的页面放在 files/draft，而不是 cache。系统回收进程或用户重启后仍可恢复。
 * manifest 采用原子替换写入，避免写一半留下不可读草稿。
 */
object DraftStore {
    private const val MANIFEST = "draft.json"

    fun load(context: Context): ScanDraft {
        val file = File(FileStore.draftDir(context), MANIFEST)
        if (!file.exists()) return ScanDraft()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val pagesJson = json.optJSONArray("pages") ?: JSONArray()
            val pages = buildList {
                for (index in 0 until pagesJson.length()) {
                    val item = pagesJson.optJSONObject(index) ?: continue
                    val source = item.optString("sourcePath")
                    val rendered = item.optString("renderedPath")
                    if (!File(source).exists() || !File(rendered).exists()) continue
                    val filter = runCatching { ScanFilter.valueOf(item.optString("filter", "AUTO")) }
                        .getOrDefault(ScanFilter.AUTO)
                    add(
                        DraftPage(
                            id = item.optString("id"),
                            sourcePath = source,
                            renderedPath = rendered,
                            width = item.optInt("width"),
                            height = item.optInt("height"),
                            recipe = EditRecipe(
                                quarterTurns = item.optInt("quarterTurns"),
                                corners = EditRecipe.decodeCorners(item.optString("cropPoints")),
                                filter = filter,
                                brightness = item.optDouble("brightness", 0.0).toFloat(),
                                contrast = item.optDouble("contrast", 1.0).toFloat(),
                                fineRotation = item.optDouble("fineRotation", 0.0).toFloat()
                            ),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }
            ScanDraft(
                title = json.optString("title"),
                appendDocId = json.optString("appendDocId").takeIf { it.isNotBlank() },
                pages = pages,
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }.getOrElse { ScanDraft() }
    }

    fun start(context: Context, appendDocId: String? = null): ScanDraft {
        clear(context)
        return ScanDraft(appendDocId = appendDocId).also { save(context, it) }
    }

    fun setTitle(context: Context, draft: ScanDraft, title: String): ScanDraft =
        draft.copy(title = title, updatedAt = System.currentTimeMillis()).also { save(context, it) }

    fun setAppendDoc(context: Context, draft: ScanDraft, docId: String): ScanDraft =
        draft.copy(appendDocId = docId, updatedAt = System.currentTimeMillis()).also { save(context, it) }

    fun add(context: Context, draft: ScanDraft, result: EditResult): ScanDraft {
        val pageId = "p${System.currentTimeMillis().toString(36)}${UUID.randomUUID().toString().take(5)}"
        val page = persistResult(context, pageId, result)
        return draft.copy(pages = draft.pages + page, updatedAt = System.currentTimeMillis()).also { save(context, it) }
    }

    fun replace(context: Context, draft: ScanDraft, pageId: String, result: EditResult): ScanDraft {
        val old = draft.pages.firstOrNull { it.id == pageId } ?: return draft
        val replacement = persistResult(context, pageId, result, old.createdAt)
        return draft.copy(
            pages = draft.pages.map { if (it.id == pageId) replacement else it },
            updatedAt = System.currentTimeMillis()
        ).also { save(context, it) }
    }

    fun remove(context: Context, draft: ScanDraft, pageId: String): ScanDraft {
        draft.pages.firstOrNull { it.id == pageId }?.let {
            File(it.sourcePath).delete()
            File(it.renderedPath).delete()
        }
        return draft.copy(
            pages = draft.pages.filterNot { it.id == pageId },
            updatedAt = System.currentTimeMillis()
        ).also { save(context, it) }
    }

    fun move(context: Context, draft: ScanDraft, from: Int, to: Int): ScanDraft {
        if (from !in draft.pages.indices || to !in draft.pages.indices || from == to) return draft
        val reordered = draft.pages.toMutableList().apply { add(to, removeAt(from)) }
        return draft.copy(pages = reordered, updatedAt = System.currentTimeMillis()).also { save(context, it) }
    }

    fun clear(context: Context) {
        val dir = FileStore.draftDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        FileStore.clearSession(context)
    }

    private fun persistResult(
        context: Context,
        pageId: String,
        result: EditResult,
        createdAt: Long = System.currentTimeMillis()
    ): DraftPage {
        val source = File(FileStore.draftSourceDir(context), "$pageId.jpg")
        val rendered = File(FileStore.draftRenderedDir(context), "$pageId.jpg")
        if (result.sourceFile.absolutePath != source.absolutePath) result.sourceFile.copyTo(source, overwrite = true)
        if (result.renderedFile.absolutePath != rendered.absolutePath) result.renderedFile.copyTo(rendered, overwrite = true)
        return DraftPage(pageId, source.absolutePath, rendered.absolutePath, result.width, result.height, result.recipe, createdAt)
    }

    private fun save(context: Context, draft: ScanDraft) {
        val root = FileStore.draftDir(context)
        val target = File(root, MANIFEST)
        val staged = File(root, "$MANIFEST.tmp")
        val pages = JSONArray()
        draft.pages.forEach { page ->
            pages.put(JSONObject().apply {
                put("id", page.id)
                put("sourcePath", page.sourcePath)
                put("renderedPath", page.renderedPath)
                put("width", page.width)
                put("height", page.height)
                put("quarterTurns", page.recipe.quarterTurns)
                put("cropPoints", page.recipe.encodeCorners())
                put("filter", page.recipe.filter.name)
                put("brightness", page.recipe.brightness.toDouble())
                put("contrast", page.recipe.contrast.toDouble())
                put("fineRotation", page.recipe.fineRotation.toDouble())
                put("createdAt", page.createdAt)
            })
        }
        staged.writeText(JSONObject().apply {
            put("title", draft.title)
            put("appendDocId", draft.appendDocId ?: "")
            put("updatedAt", draft.updatedAt)
            put("pages", pages)
        }.toString(), Charsets.UTF_8)
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }
}
