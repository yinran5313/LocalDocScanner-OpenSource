package com.localdoc.scanner.export

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.localdoc.scanner.ocr.OcrTextBox
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.cos.COSName

class SearchablePdfExporterTest {
    @Test fun selectedLetterAndImageSizesApplyToSearchablePdf() {
        val dir = kotlin.io.path.createTempDirectory("searchable-size-").toFile()
        try {
            val image = File(dir, "page.jpg")
            checkNotNull(javaClass.getResourceAsStream("/searchable-pdf-page.jpg")).use { input -> image.outputStream().use(input::copyTo) }
            for (size in listOf(PdfExporter.PageSize.LETTER, PdfExporter.PageSize.FIT_IMAGE)) {
                val output = File(dir, "$size.pdf")
                File("src/main/assets/fonts/LXGWWenKai-Regular.ttf").inputStream().use { font -> output.outputStream().use { stream ->
                    assertTrue(SearchablePdfExporter.export(listOf(SearchablePdfPage(image, "已校正文字", listOf(OcrTextBox("已校正文字", .1f, .2f, .8f, .3f)))), stream, font, size) { document, file ->
                        file.inputStream().use { PDImageXObject(document, it, COSName.DCT_DECODE, 320, 480, 8, PDDeviceRGB.INSTANCE) }
                    })
                } }
                PDDocument.load(output).use { document ->
                    assertEquals(if (size == PdfExporter.PageSize.LETTER) 612f else 320f, document.getPage(0).mediaBox.width, .01f)
                    assertEquals(if (size == PdfExporter.PageSize.LETTER) 792f else 480f, document.getPage(0).mediaBox.height, .01f)
                    assertTrue(PDFTextStripper().getText(document).contains("已校正文字"))
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun positionedLayerFollowsOcrBoxAndDoesNotTruncateLongText() {
        val dir = kotlin.io.path.createTempDirectory("positioned-pdf-").toFile()
        val image = File(dir, "page.jpg")
        val output = File(dir, "searchable.pdf")
        checkNotNull(javaClass.getResourceAsStream("/searchable-pdf-page.jpg")).use { input -> image.outputStream().use(input::copyTo) }
        val longText = "测试".repeat(85) + "END"
        File("src/main/assets/fonts/LXGWWenKai-Regular.ttf").inputStream().use { font ->
            output.outputStream().use { stream ->
                assertTrue(SearchablePdfExporter.export(listOf(SearchablePdfPage(image, longText,
                    listOf(OcrTextBox(longText, .2f, .3f, .8f, .4f)))), stream, font) { document, file ->
                    // Construct fixture JPEG without Android BitmapFactory (stubbed in JVM tests).
                    file.inputStream().use { PDImageXObject(document, it, COSName.DCT_DECODE, 320, 480, 8, PDDeviceRGB.INSTANCE) }
                })
            }
        }
        val positions = mutableListOf<TextPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) { positions.add(text); super.processTextPosition(text) }
        }
        PDDocument.load(output).use { document ->
            assertTrue(stripper.getText(document).contains(longText))
            assertEquals(longText.length, positions.size)
            val box = document.getPage(0).mediaBox
            val imageWidth = box.height * 320f / 480f
            assertEquals((box.width - imageWidth) / 2f + imageWidth * .2f, positions.first().x, 1f)
            assertTrue(positions.last().x > positions.first().x)
        }
        dir.deleteRecursively()
    }
    @Test
    fun exportedImagePdfContainsExtractableChineseOcrText() {
        val dir = kotlin.io.path.createTempDirectory("searchable-pdf-").toFile()
        val image = File(dir, "page.jpg")
        val output = File(dir, "searchable.pdf")
        checkNotNull(javaClass.getResourceAsStream("/searchable-pdf-page.jpg")).use { input ->
            image.outputStream().use(input::copyTo)
        }

        File("src/main/assets/fonts/LXGWWenKai-Regular.ttf").inputStream().use { font ->
            output.outputStream().use { stream ->
                assertTrue(
                    SearchablePdfExporter.export(
                        listOf(SearchablePdfPage(image, "测试文字 ABC 123")),
                        stream,
                        font
                    )
                )
            }
        }
        val extracted = PDDocument.load(output).use { PDFTextStripper().getText(it) }
        assertTrue(extracted.contains("测试文字"))
        assertTrue(extracted.contains("ABC 123"))
        dir.deleteRecursively()
    }
}
