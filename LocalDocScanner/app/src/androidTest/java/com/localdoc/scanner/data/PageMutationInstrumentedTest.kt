package com.localdoc.scanner.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.data.db.AppDatabase
import com.localdoc.scanner.edit.EditRecipe
import com.localdoc.scanner.edit.EditResult
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PageMutationInstrumentedTest {
    @Test fun duplicateInsertsRowAndEditingInvalidatesOnlyItsOcr() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocRepository(context); val dao = AppDatabase.get(context).docDao()
        val id = repo.createDoc("页面变更测试")
        val input = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        try {
            val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.WHITE); assertTrue(ImageIo.saveJpeg(bitmap, input)) } finally { bitmap.recycle() }
            repo.appendProcessedPage(id, input)
            val original = repo.pages(id).single()
            dao.updatePage(original.copy(ocrText = "原文字", ocrMode = "medium", ocrLayout = com.localdoc.scanner.ocr.OcrLayout.encode(listOf(OcrTextBox("原文字", .1f, .1f, .8f, .2f)))))
            repo.rebuildDocumentOcr(id)
            repo.setOcrText(id, "独立校订稿")
            repo.duplicatePage(id, original.id)
            assertEquals(listOf(0, 1), repo.pages(id).map { it.pageIndex })
            val copy = repo.pages(id)[1]
            repo.correctOcrLine(copy.id, 0, "修正文字", copy.updatedAt)
            assertEquals("修正文字", repo.pages(id)[1].ocrText)
            repo.updatePage(copy.id, EditResult(input, input, EditRecipe(), 80, 100))
            val updated = repo.pages(id)[1]
            assertEquals("", updated.ocrText); assertEquals("", updated.ocrLayout)
            assertNotEquals(copy.filePath, updated.filePath); assertFalse(File(copy.filePath).exists())
            assertTrue(File(original.filePath).isFile)
            assertEquals("独立校订稿", dao.getDoc(id)!!.ocrCorrection)
            assertFalse(dao.getDoc(id)!!.ocrText.contains("修正文字"))
            repo.trashPage(id, original.id)
            assertEquals("", dao.getDoc(id)!!.ocrText)
        } finally { repo.deleteForever(id); input.delete() }
    }
}
