package com.localdoc.scanner.jobs

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolResumeInstrumentedTest {
    @Test fun newProcessorReusesCompletedOcrPageAfterCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val inputs = (0..1).map { File(context.cacheDir, "$id-$it.jpg") }
        try {
            val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.WHITE); inputs.forEach { assertTrue(ImageIo.saveJpeg(bitmap, it)) } } finally { bitmap.recycle() }
            val request = ToolRequest(TOOL_ENTRIES.first { it.id == "ocr" }, inputs, inputs.map { it.name })
            val spec = ToolTaskSpec(id, request, mapOf("precise" to "1", "searchable" to "false"), inputs.associate { it.absolutePath to ToolCheckpoints.hash(it) })
            fun processor(engine: OcrEngine) = ToolTaskProcessor(context, spec, null) { _, _, _ -> }.apply { recognizerForTest = engine }
            var firstCalls = 0
            val interrupted = processor(object : OcrEngine {
                override val available = true; override val label = "fake"
                override suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome {
                    assertTrue(precise)
                    if (++firstCalls == 2) throw kotlinx.coroutines.CancellationException("模拟进程退出")
                    return OcrOutcome("第一张完成", 1, 1, 0)
                }
            })
            try { interrupted.run(); fail("应中断") } catch (_: kotlinx.coroutines.CancellationException) { }
            val receipts = ToolCheckpoints(ToolTasks.dir(context, id), ToolTasks.gson)
            assertNotNull(receipts.load("ocr:0:0")); assertNull(receipts.load("ocr:1:0"))
            var resumedCalls = 0
            val resumed = processor(object : OcrEngine {
                override val available = true; override val label = "fake"
                override suspend fun recognize(bitmap: Bitmap, precise: Boolean) = OcrOutcome("第二张完成", 1, 1, 0).also { resumedCalls++ }
            }).run()
            assertEquals(1, resumedCalls)
            assertTrue(resumed.copyText!!.contains("第一张完成")); assertTrue(resumed.copyText.contains("第二张完成"))
            assertEquals(resumed.copyText, resumed.files.single().readText())
        } finally { inputs.forEach { it.delete() }; ToolTasks.dir(context, id).deleteRecursively() }
    }
}
