package com.localdoc.scanner.output

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OutputSaveInstrumentedTest {
    @Test fun savingFromHistoryKeepsOriginalAndUpdatesTheSameRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "save-audit-${UUID.randomUUID()}").apply { mkdirs() }
        val original = File(root,"原文件.txt").apply { writeText("拾页中文内容 501") }
        val saved = File(root,"另存文件.txt")
        val record = OutputHistoryStore.recordGenerated(context,original,"text/plain")
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",saved)
        try {
            OutputHistoryStore.saveCopy(context,record,FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",original))
            assertEquals("拾页中文内容 501", original.readText())
            OutputHistoryStore.saveCopy(context,record,uri)
            assertEquals(original.readText(), saved.readText())
            assertEquals("拾页中文内容 501", original.readText())
            val current = OutputHistoryStore.all(context).single { it.id==record.id }
            assertEquals(uri.toString(),current.savedUri)
            assertEquals(original.absolutePath,current.internalPath)
            assertEquals("另存文件.txt",current.name)
            // URI-only records remain saveable after the old internal working copy disappears.
            original.delete()
            val second=File(root,"再次另存.txt")
            OutputHistoryStore.saveCopy(context,current,FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",second))
            assertEquals(saved.readText(),second.readText())
        } finally { root.deleteRecursively() }
    }
}
