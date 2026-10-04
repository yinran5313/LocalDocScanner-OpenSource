package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.contentstream.operator.Operator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfOriginalTextEditorTest {
    @Test fun replacingTextChangesOriginalObjectAndPreservesAdvanceAndOtherText() {
        val folder = Files.createTempDirectory("original-pdf-text").toFile()
        try {
            val source = File(folder, "source.pdf"); val out = File(folder, "edited.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(40f, 700f)
                    stream.setCharacterSpacing(1f); stream.showText("Original"); stream.showText("Next"); stream.endText()
                }
                doc.save(source)
            }
            val bytes = source.readBytes()
            val entries = PdfOriginalTextEditor.list(source, 0)
            assertEquals(listOf("Original", "Next"), entries.map { it.text })
            assertTrue(PdfOriginalTextEditor.apply(source, out, PdfTextChange(entries[0].key, 0, "New")))
            PDDocument.load(out).use { doc ->
                val text = PDFTextStripper().getText(doc)
                assertFalse(text.contains("Original")); assertTrue(text.contains("New")); assertTrue(text.contains("Next"))
                val parser = PDFStreamParser(doc.getPage(0)); parser.parse()
                val array = parser.tokens.filterIsInstance<COSArray>().single()
                assertTrue((array.get(1) as COSNumber).floatValue() < 0f)
            }
            assertArrayEquals(bytes, source.readBytes())
            // The next object remains identifiable after Tj becomes TJ.
            assertTrue(PdfOriginalTextEditor.list(out, 0).any { it.key == entries[1].key })
        } finally { folder.deleteRecursively() }
    }
    @Test fun arrayTextIsReplacedWithoutDroppingExistingKerning() {
        val folder = Files.createTempDirectory("array-pdf-text").toFile()
        try {
            val source = File(folder, "source.pdf"); val out = File(folder, "out.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.showTextWithPositioning(arrayOf("Wide", -50f, "Keep")); stream.endText()
                }
                doc.save(source)
            }
            val entries = PdfOriginalTextEditor.list(source, 0)
            PdfOriginalTextEditor.apply(source, out, PdfTextChange(entries[0].key, 0, "Hi"))
            PDDocument.load(out).use { doc ->
                val parser = PDFStreamParser(doc.getPage(0)); parser.parse()
                val array = parser.tokens.filterIsInstance<COSArray>().single()
                assertEquals(-50f, (array.get(2) as COSNumber).floatValue(), .01f)
                assertTrue(PDFTextStripper().getText(doc).contains("Keep"))
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun missingGlyphAndOversizedTextAreRejectedWithoutOutput() {
        val folder = Files.createTempDirectory("refuse-pdf-text").toFile()
        try {
            val source = File(folder, "source.pdf"); val out = File(folder, "out.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { s -> s.beginText(); s.setFont(PDType1Font.HELVETICA, 12f); s.showText("Short"); s.endText() }
                doc.save(source)
            }
            val entry = PdfOriginalTextEditor.list(source, 0).single()
            assertThrows(IllegalArgumentException::class.java) { PdfOriginalTextEditor.apply(source, out, PdfTextChange(entry.key, 0, "中文")) }
            assertThrows(IllegalArgumentException::class.java) { PdfOriginalTextEditor.apply(source, out, PdfTextChange(entry.key, 0, "Very long text that cannot fit")) }
            assertFalse(out.exists()); assertFalse(folder.listFiles()!!.any { it.extension == "part" })
        } finally { folder.deleteRecursively() }
    }
}
