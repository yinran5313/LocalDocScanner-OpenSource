package com.localdoc.scanner

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V51MigrationAndroidTest {
    @Test fun actualRoomUpgradeKeepsVersion7OcrCorrectionsAndPageGeometry(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-proof-${System.nanoTime()}.db"; val path=app.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("schema-v7.sql").bufferedReader().readText()
            schema.split(';').map(String::trim).filter(String::isNotBlank).forEach(db::execSQL)
            db.execSQL("INSERT INTO docs(id,title,createdAt,updatedAt,pageCount,sizeBytes,tags,ocrText,locked,deleted,ocrCorrection) VALUES ('old-doc','旧文档',1,2,1,100,'旧标签','识别原文',0,0,'人工校订')")
            db.execSQL("INSERT INTO pages(id,docId,pageIndex,filePath,width,height,sourcePath,cropPoints,ocrText,ocrLayout,ocrMode) VALUES ('old-page','old-doc',0,'/old.jpg',800,1200,'/original.jpg','0,0;1,0;1,1;0,1','已识别','[{\"text\":\"旧字\"}]','medium')")
            db.version=7
        }
        val upgraded=Room.databaseBuilder(app,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_7_8).build()
        try {
            val doc=upgraded.docDao().getDoc("old-doc")!!; val page=upgraded.docDao().getPage("old-page")!!
            assertEquals("SCAN",doc.kind); assertEquals("",doc.filePath); assertEquals("人工校订",doc.ocrCorrection)
            assertEquals("/original.jpg",page.sourcePath); assertEquals("medium",page.ocrMode); assertTrue(page.ocrLayout.contains("旧字")); assertEquals(1200,page.height)
        } finally { upgraded.close(); app.deleteDatabase(name) }
        Unit
    }
}
