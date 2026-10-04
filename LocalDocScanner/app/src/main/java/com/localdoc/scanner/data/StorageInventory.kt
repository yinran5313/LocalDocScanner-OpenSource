package com.localdoc.scanner.data

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.localdoc.scanner.data.db.AppDatabase
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.output.OutputHistoryStore
import java.io.File

data class StorageFile(val path: String, val category: String, val bytes: Long, val referenced: Boolean)

/** Inventory only owned working data; runtime assets, databases and credentials are excluded. */
object StorageInventory {
    suspend fun inspect(context: Context): List<StorageFile> {
        val references = mutableSetOf<String>()
        fun reference(path: String) { if (path.isNotBlank()) runCatching { references += File(path).canonicalPath } }
        fun json(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) -> if (key.startsWith(context.filesDir.absolutePath)) reference(key); json(child) }
                value.isJsonArray -> value.asJsonArray.forEach(::json)
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    val text = value.asString
                    if (text.startsWith(context.filesDir.absolutePath)) reference(text)
                    else if (text.startsWith("file://")) android.net.Uri.parse(text).path?.let(::reference)
                    else if (text.startsWith("{") || text.startsWith("[")) runCatching { json(JsonParser.parseString(text)) }
                }
            }
        }
        val dao = AppDatabase.get(context).docDao()
        dao.getAllDocs().forEach { doc -> dao.getAllPages(doc.id).forEach { reference(it.sourcePath); reference(it.filePath) } }
        val draft = DraftStore.load(context)
        draft.pages.forEach { reference(it.sourcePath); reference(it.renderedPath) }
        val drafts = context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE).all
        drafts.keys.filter { it.contains(":") }.forEach { key ->
            key.substringAfter(':').substringBeforeLast(':').split('|').filter { it.startsWith(context.filesDir.absolutePath) }.forEach(::reference)
        }
        drafts.values.filterIsInstance<String>()
            .forEach { runCatching { json(JsonParser.parseString(it)) } }
        OutputHistoryStore.all(context).forEach { reference(it.internalPath) }
        val protectedDirectories = mutableSetOf<String>()
        ToolTasks.all(context).forEach { task ->
            val dir = ToolTasks.dir(context, task.id)
            if (task.state != ToolTaskState.SUCCEEDED) protectedDirectories += dir.canonicalPath
            runCatching { json(JsonParser.parseString(File(dir, "spec.json").readText())) }
            runCatching { json(JsonParser.parseString(File(dir, "result.json").readText())) }
        }
        // Never classify a damaged draft or an Office recovery copy as disposable.
        if (draft.recoveryError.isNotBlank()) protectedDirectories += FileStore.draftDir(context).canonicalPath
        listOf("office-recovery", "ocr-jobs").forEach { protectedDirectories += File(context.filesDir, it).canonicalPath }
        ToolTasks.root(context).listFiles()?.filter { it.isDirectory && ToolTasks.status(context, it.name) == null }
            ?.forEach { protectedDirectories += it.canonicalPath }
        val officeProcesses = context.getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses
        if (officeProcesses == null || officeProcesses.any { it.processName == "${context.packageName}:office" }) {
            protectedDirectories += File(context.filesDir, "office-work").canonicalPath
        }
        val categories = linkedMapOf("library" to "扫描文档", "draft" to "扫描草稿", "tool-import" to "导入文件",
            "export" to "导出成果", "tool-jobs" to "工具任务", "office-work" to "Office工作副本",
            "office-results" to "Office产物", "office-recovery" to "Office恢复副本", "ocr-jobs" to "文档OCR回执")
        return categories.flatMap { (name, category) ->
            val dir = File(context.filesDir, name)
            if (!dir.isDirectory) emptyList() else dir.walkTopDown().filter { it.isFile }.map { file ->
                val path = file.canonicalPath
                val durableJournal = file.extension == "json" && name in setOf("draft", "tool-jobs", "ocr-jobs")
                val recent = System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000L
                StorageFile(path, category, file.length(), path in references || durableJournal || recent ||
                    protectedDirectories.any { path.startsWith(it + File.separator) })
            }.toList()
        }.distinctBy { it.path }
    }

    suspend fun removeSelected(context: Context, paths: Set<String>): Int {
        val root = context.filesDir.canonicalPath + File.separator
        // Re-read live references immediately before removal; stale UI selections are insufficient.
        val allowed = inspect(context).filter { !it.referenced && it.path in paths }.map { it.path }
        return allowed.count { path ->
            val file = File(path)
            require(file.canonicalPath.startsWith(root)) { "路径超出应用工作目录" }
            file.delete()
        }
    }
}
