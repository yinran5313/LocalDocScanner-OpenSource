package com.localdoc.scanner.data

import android.content.Context
import java.io.File

/** 全部数据落在 App 私有目录，不申请任何外部存储权限，也就不存在联网与上传路径 */
object FileStore {

    fun root(context: Context): File =
        File(context.filesDir, "library").apply { mkdirs() }

    fun docDir(context: Context, docId: String): File =
        File(root(context), docId).apply { mkdirs() }

    fun sourceDir(context: Context, docId: String): File =
        File(docDir(context, docId), "source").apply { mkdirs() }

    fun pageDir(context: Context, docId: String): File =
        File(docDir(context, docId), "pages").apply { mkdirs() }

    fun sourceFile(context: Context, docId: String, pageId: String): File =
        File(sourceDir(context, docId), "$pageId.jpg")

    fun pageFile(context: Context, docId: String, pageId: String): File =
        File(pageDir(context, docId), "$pageId.jpg")

    /** 旧V1使用按序号命名的页面，迁移后仍保留可读。 */
    fun legacyPageFile(context: Context, docId: String, index: Int): File =
        File(docDir(context, docId), "p$index.jpg")

    fun exportDir(context: Context): File =
        File(context.filesDir, "export").apply { mkdirs() }

    fun sessionDir(context: Context): File =
        File(context.cacheDir, "session").apply { mkdirs() }

    /** V1编辑器工作文件；后续确认页面时会移动到持久草稿目录。 */
    fun draftWorkDir(context: Context): File =
        File(context.cacheDir, "edit-work").apply { mkdirs() }

    fun draftDir(context: Context): File =
        File(context.filesDir, "draft/current").apply { mkdirs() }

    fun draftSourceDir(context: Context): File =
        File(draftDir(context), "source").apply { mkdirs() }

    fun draftRenderedDir(context: Context): File =
        File(draftDir(context), "rendered").apply { mkdirs() }

    fun draftInboxDir(context: Context): File =
        File(draftDir(context), "inbox").apply { mkdirs() }

    fun importDir(context: Context): File =
        File(context.filesDir, "tool-import").apply { mkdirs() }

    fun newSessionFile(context: Context): File =
        File(sessionDir(context), "shot_${System.currentTimeMillis()}.jpg")

    fun clearSession(context: Context) {
        sessionDir(context).listFiles()?.forEach { it.delete() }
        draftWorkDir(context).listFiles()?.forEach { it.delete() }
    }
}
