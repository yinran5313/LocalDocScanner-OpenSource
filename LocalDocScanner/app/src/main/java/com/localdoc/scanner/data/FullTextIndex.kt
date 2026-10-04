package com.localdoc.scanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.office.OpenXmlEditor
import com.localdoc.scanner.pdf.PdfTools
import java.io.File

data class SearchHit(val key: String, val name: String, val path: String, val snippet: String)

/** Android SQLite FTS4 provides an offline index; LIKE also supports Chinese substrings. */
class FullTextIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "fulltext_v44.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE content_index USING fts4(item_key, name, path, body, notindexed=item_key, notindexed=path)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun put(key: String, name: String, path: String, body: String) {
        writableDatabase.delete("content_index", "item_key=?", arrayOf(key))
        writableDatabase.insertOrThrow("content_index", null, ContentValues().apply {
            put("item_key", key); put("name", name); put("path", path); put("body", body)
        })
    }
    fun indexScan(doc: DocItem) = put("scan:${doc.id}", doc.title, "", listOf(doc.folder.orEmpty(), doc.tags, doc.ocrText).joinToString("\n"))
    fun indexFile(file: File, name: String = file.name) {
        require(file.length() <= 60L * 1024 * 1024) { "${file.name}超过60MB，请拆分后索引" }
        val body = when {
            file.extension.lowercase() == "pdf" -> PdfTools.extractText(file)
            OpenXmlEditor.supports(file.name) -> OpenXmlEditor.read(file).units.joinToString("\n") { "${it.section} ${it.text}" }
            file.extension.lowercase() in setOf("txt", "csv", "md", "json") -> file.readText()
            else -> error("${file.name}没有可提取的文字格式，图片请先OCR")
        }
        require(body.isNotBlank()) { "${file.name}没有文字层或受密码保护，请先OCR/解锁" }
        put("file:${file.absolutePath}", name, file.absolutePath, body)
    }
    fun removeScan(id: String) { writableDatabase.delete("content_index", "item_key=?", arrayOf("scan:$id")) }
    fun clear() { writableDatabase.delete("content_index", null, null) }
    fun search(query: String): List<SearchHit> {
        val literal = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return readableDatabase.rawQuery("SELECT item_key,name,path,body FROM content_index WHERE name LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' LIMIT 100", arrayOf(literal, literal)).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val body = cursor.getString(3).orEmpty()
                val start = (body.indexOf(query, ignoreCase = true).coerceAtLeast(0) - 40).coerceAtLeast(0)
                add(SearchHit(cursor.getString(0), cursor.getString(1), cursor.getString(2), body.substring(start, (start + 180).coerceAtMost(body.length))))
            } }
        }
    }
}
