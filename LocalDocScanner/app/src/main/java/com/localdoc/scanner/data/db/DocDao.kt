package com.localdoc.scanner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocDao {

    @Query("SELECT * FROM docs WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeDocs(): Flow<List<DocEntity>>

    @Query("SELECT * FROM docs WHERE deleted = 1 ORDER BY updatedAt DESC")
    fun observeTrashed(): Flow<List<DocEntity>>

    @Query("SELECT * FROM docs WHERE id = :id")
    suspend fun getDoc(id: String): DocEntity?

    @Query("SELECT * FROM docs ORDER BY createdAt")
    suspend fun getAllDocs(): List<DocEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoc(doc: DocEntity)

    @Update
    suspend fun updateDoc(doc: DocEntity)

    @Query("UPDATE docs SET deleted = 1 WHERE id = :id")
    suspend fun moveToTrash(id: String)

    @Query("UPDATE docs SET deleted = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreDoc(id: String, updatedAt: Long)

    @Query("DELETE FROM docs WHERE id = :id")
    suspend fun deleteForever(id: String)

    @Query("SELECT * FROM pages WHERE docId = :docId AND deleted = 0 ORDER BY pageIndex")
    suspend fun getPages(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY pageIndex")
    suspend fun getAllPages(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPage(pageId: String): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("UPDATE pages SET deleted = 1, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun trashPage(pageId: String, updatedAt: Long)

    @Query("UPDATE pages SET deleted = 0, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun restorePage(pageId: String, updatedAt: Long)

    @Query("DELETE FROM pages WHERE docId = :docId")
    suspend fun deletePages(docId: String)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePageForever(pageId: String)

    @Query("SELECT * FROM docs WHERE deleted = 0 AND (title LIKE '%' || :q || '%' OR ocrText LIKE '%' || :q || '%') ORDER BY updatedAt DESC")
    suspend fun search(q: String): List<DocEntity>
}
