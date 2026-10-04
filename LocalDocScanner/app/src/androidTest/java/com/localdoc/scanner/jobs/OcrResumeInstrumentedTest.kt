package com.localdoc.scanner.jobs

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.localdoc.scanner.data.DocRepository
import com.localdoc.scanner.data.db.AppDatabase
import com.localdoc.scanner.ocr.OcrEngine
import com.localdoc.scanner.ocr.OcrOutcome
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OcrResumeInstrumentedTest {
    @Test fun cancellationPreservesFirstPageAndNextWorkerOnlyRecognizesUnfinishedPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocRepository(context); val docId = repo.createDoc("断点测试")
        val id = UUID.randomUUID().toString()
        val input = File(context.cacheDir, "$id.jpg")
        try {
            val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.WHITE); assertTrue(ImageIo.saveJpeg(bitmap, input)) } finally { bitmap.recycle() }
            repeat(2) { repo.appendProcessedPage(docId, input) }
            val pages = repo.pages(docId)
            OcrCheckpointStore.save(OcrJobs.root(context), OcrCheckpoint(id, docId, true, pages.map {
                OcrPageCheckpoint(it.id, OcrCheckpointStore.hash(File(it.filePath)), OcrJobs.recipe(it))
            }))
            var firstCalls = 0
            fun worker(engine: OcrEngine) = TestListenableWorkerBuilder<DocumentOcrWorker>(context)
                .setInputData(workDataOf("journal" to id)).build().apply { recognizerForTest = engine }
            val interrupted = worker(object : OcrEngine {
                override val available = true; override val label = "fake"
                override suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome {
                    if (++firstCalls == 2) throw kotlinx.coroutines.CancellationException("模拟进程停止")
                    return OcrOutcome("第一张完成", 1, 1, 0)
                }
            })
            try { interrupted.doWork(); fail("应中断") } catch (_: kotlinx.coroutines.CancellationException) { }
            val checkpoint = OcrCheckpointStore.load(OcrJobs.root(context), id)
            assertTrue(checkpoint.pages[0].done); assertFalse(checkpoint.pages[1].done)
            val firstSavedAt = repo.pages(docId)[0].ocrUpdatedAt
            var resumedCalls = 0
            val resumed = worker(object : OcrEngine {
                override val available = true; override val label = "fake"
                override suspend fun recognize(bitmap: Bitmap, precise: Boolean) = OcrOutcome("第二张完成", 1, 1, 0).also { resumedCalls++ }
            })
            assertEquals(androidx.work.ListenableWorker.Result.success(workDataOf("done" to 2, "total" to 2, "failed" to 0, "error" to "")), resumed.doWork())
            assertEquals(1, resumedCalls)
            assertEquals(firstSavedAt, repo.pages(docId)[0].ocrUpdatedAt)
            val dao = AppDatabase.get(context).docDao()
            assertEquals(0, dao.setOcrIfUnchanged(pages[0].id, -1, 1, "旧任务", "", "tiny"))
            assertEquals("第一张完成", dao.getPage(pages[0].id)?.ocrText)
        } finally { repo.deleteForever(docId); input.delete(); OcrCheckpointStore.file(OcrJobs.root(context), id).delete() }
    }
}
