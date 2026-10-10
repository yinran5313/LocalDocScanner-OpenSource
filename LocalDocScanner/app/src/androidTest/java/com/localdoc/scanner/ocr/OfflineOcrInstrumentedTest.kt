package com.localdoc.scanner.ocr

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.export.SearchablePdfExporter
import com.localdoc.scanner.export.SearchablePdfPage
import com.localdoc.scanner.pdf.PdfReadSession
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OfflineOcrInstrumentedTest {
    @Test fun bothModelsLoadOfflineAndMediumExportsSearchablePdf() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 58f; typeface = Typeface.DEFAULT }
            drawText("拾页文档 测试编号 501", 60f, 140f, paint)
            drawText("SHIYE 501 Preview", 60f, 280f, paint)
            drawText("金额 1234.56", 60f, 420f, paint)
        }
        val engine = PaddleOcrEngine(context)
        val root = File(context.cacheDir, "ocr-audit-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val tiny = engine.recognize(bitmap, false)
            assertTrue("Tiny must load and recognize fixture: ${tiny.text}", tiny.text.contains("501"))
            val medium = engine.recognize(bitmap, true)
            assertTrue("Medium must recognize fixture: ${medium.text}", medium.text.contains("501"))
            assertTrue("Medium must provide valid text positions", medium.boxes.isNotEmpty() && medium.boxes.all(OcrLayout::valid))
            val image = File(root, "page.jpg"); assertTrue(ImageIo.saveJpeg(bitmap, image))
            val output = File(root, "searchable.pdf")
            // Use the same writer as user OCR tools, including the positioned text layer.
            output.outputStream().use { stream -> SearchablePdfExporter.export(context, listOf(SearchablePdfPage(image, medium.text, medium.boxes)), stream) }
            PdfReadSession.open(output).use { session ->
                assertEquals(1, session.pageCount)
                assertTrue(session.pageText(0).contains("501"))
                val rendered = session.render(0, 900)
                assertNotNull(rendered); rendered?.recycle()
            }
        } finally { engine.close(); bitmap.recycle(); root.deleteRecursively() }
    }
}
