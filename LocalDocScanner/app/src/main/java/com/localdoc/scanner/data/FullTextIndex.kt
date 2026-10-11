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
class FullTextIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "fulltext_v44.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE content_index USING fts4(item_key, name, path, body, notindexed=item_key, notindexed=path)")
        metadata(db)
    }
    private fun metadata(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS index_versions(item_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { metadata(db) }
    private fun current(key: String, fingerprint: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM index_versions WHERE item_key=? AND fingerprint=?",arrayOf(key,fingerprint)).use { it.moveToFirst() }
    private fun put(key: String, name: String, path: String, body: String, fingerprint: String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
        db.delete("content_index", "item_key=?", arrayOf(key))
        db.insertOrThrow("content_index", null, ContentValues().apply {
            put("item_key", key); put("name", name); put("path", path); put("body", body)
        })
        db.insertWithOnConflict("index_versions",null,ContentValues().apply { put("item_key",key); put("fingerprint",fingerprint) },SQLiteDatabase.CONFLICT_REPLACE)
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun indexScan(doc: DocItem) {
        val body=listOf(doc.folder.orEmpty(),doc.tags,doc.ocrText).joinToString("\n")
        val version=java.security.MessageDigest.getInstance("SHA-256").digest((doc.title+body).toByteArray()).joinToString("") { "%02x".format(it) }
        if(!current("scan:${doc.id}",version)) put("scan:${doc.id}",doc.title,"",body,version)
    }
    fun indexFile(file: File, name: String = file.name, password: String = "") {
        require(file.isFile && file.length() <= 120L * 1024 * 1024) { "${file.name}不存在或超过120MB，请拆分后索引" }
        val version="${file.length()}:${file.lastModified()}:$name"
        if(current("file:${file.absolutePath}",version)) return
        val body = when {
            file.extension.lowercase() == "pdf" -> com.tom_roush.pdfbox.pdmodel.PDDocument.load(file,password,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(16L*1024*1024)).use { doc ->
                require(doc.currentAccessPermission.canExtractContent()) { "此PDF不允许提取文字" }
                buildString { for(page in 1..doc.numberOfPages) {
                    append(com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage=page; endPage=page; sortByPosition=true }.getText(doc))
                    require(length <= 2_000_000) { "文字量超过200万字符，请拆分后索引" }
                } }
            }
            file.extension.lowercase() in setOf("doc","xls","ppt") -> com.localdoc.scanner.office.LegacyOfficeText.read(file)
            OpenXmlEditor.supports(file.name) -> OpenXmlEditor.read(file).units.joinToString("\n") { "${it.section} ${it.text}" }
            file.extension.lowercase() in setOf("txt", "csv", "md", "json") -> file.readText()
            else -> error("${file.name}没有可提取的文字格式，图片请先OCR")
        }
        require(body.isNotBlank()) { "${file.name}没有文字层或受密码保护，请先OCR/解锁" }
        check(version == "${file.length()}:${file.lastModified()}:$name") { "文件在索引期间被修改，请重新索引" }
        put("file:${file.absolutePath}", name, file.absolutePath, body.take(2_000_000), version)
    }
    private fun remove(key: String) { writableDatabase.delete("content_index","item_key=?",arrayOf(key)); writableDatabase.delete("index_versions","item_key=?",arrayOf(key)) }
    fun removeScan(id: String) = remove("scan:$id")
    fun removeFile(path: String) = remove("file:$path")
    fun prune(scanIds: Set<String>) {
        val remove=readableDatabase.rawQuery("SELECT item_key,path FROM content_index",null).use { cursor -> buildList { while(cursor.moveToNext()) {
            val key=cursor.getString(0); val path=cursor.getString(1)
            if(key.startsWith("scan:") && key.removePrefix("scan:") !in scanIds || key.startsWith("file:") && !File(path).isFile) add(key)
        } } }
        remove.forEach(::remove)
    }
    fun clear() { writableDatabase.delete("content_index", null, null); writableDatabase.delete("index_versions",null,null) }
    fun search(query: String): List<SearchHit> {
        if(query.isBlank()) return emptyList()
        val literal = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return readableDatabase.rawQuery("SELECT item_key,name,path,body FROM content_index WHERE name LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' LIMIT 100", arrayOf(literal, literal)).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val path = cursor.getString(2)
                if (path.isNotBlank() && !File(path).isFile) continue
                val body = cursor.getString(3).orEmpty()
                val start = (body.indexOf(query, ignoreCase = true).coerceAtLeast(0) - 40).coerceAtLeast(0)
                add(SearchHit(cursor.getString(0), cursor.getString(1), cursor.getString(2), body.substring(start, (start + 180).coerceAtMost(body.length))))
            } }
        }
    }
}
