package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfCompressionTest {
    @Test fun textOnlyPdfKeepsTextAndAnnotationsAndOriginalBytes() {
        val directory = kotlin.io.path.createTempDirectory("compression-").toFile()
        try {
            val original = File(directory, "original.pdf")
            val marked = File(directory, "marked.pdf")
            val output = File(directory, "compressed.pdf")
            PDDocument().use { document ->
                val page = PDPage(); document.addPage(page)
                val font = PDType0Font.load(document, File("src/main/assets/fonts/LXGWWenKai-Regular.ttf"))
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(font, 14f); content.newLineAtOffset(60f, 720f)
                    content.showText("压缩后仍然可以搜索"); content.endText()
                }
                document.save(original)
            }
            assertTrue(PdfOfficeTools.addNote(original, marked, 0, "保留批注", .2f, .2f))
            val result = PdfTools.compressPreservingContent(marked, output, 140, 70)
            assertTrue(result.retainedOriginal)
            assertArrayEquals(marked.readBytes(), output.readBytes())
            PDDocument.load(output).use { document ->
                assertTrue(PDFTextStripper().getText(document).contains("压缩后仍然可以搜索"))
                assertEquals("保留批注", document.getPage(0).annotations.single().contents)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesOutputThatWouldReplaceTheSource() {
        val file = kotlin.io.path.createTempFile("source-", ".pdf").toFile()
        try {
            val before = file.readBytes()
            assertThrows(IllegalArgumentException::class.java) {
                PdfTools.compressPreservingContent(file, file, 140, 70)
            }
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }
}
