package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfAnnotationEditorTest {
    @Test fun deleteThenUpdateUsesStableKeyRatherThanShiftedIndex() {
        val dir = Files.createTempDirectory("annotation-edit").toFile()
        try {
            val source = File(dir, "source.pdf"); val middle = File(dir, "middle.pdf"); val out = File(dir, "out.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                page.annotations = listOf(
                    PDAnnotationText().apply { rectangle = PDRectangle(20f, 20f, 30f, 30f); contents = "first" },
                    PDAnnotationText().apply { rectangle = PDRectangle(80f, 20f, 30f, 30f); contents = "second" },
                    PDAnnotationLink().apply { rectangle = PDRectangle(20f, 100f, 50f, 10f) }
                )
                doc.save(source)
            }
            val bytes = source.readBytes()
            val entries = PdfAnnotationEditor.list(source)
            assertFalse(entries[2].editable)
            assertTrue(PdfAnnotationEditor.apply(source, middle, PdfAnnotationChange(entries[0].key, 0, true, "")))
            assertTrue(PdfAnnotationEditor.apply(middle, out, PdfAnnotationChange(entries[1].key, 0, false, "changed")))
            PDDocument.load(out).use { doc ->
                assertEquals(2, doc.getPage(0).annotations.size)
                assertEquals("changed", doc.getPage(0).annotations[0].contents)
                assertTrue(doc.getPage(0).annotations[1] is PDAnnotationLink)
            }
            assertArrayEquals(bytes, source.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun lockedOrAmbiguousAnnotationsAndOverlongCommentsCannotBeModified() {
        val dir = Files.createTempDirectory("annotation-locked").toFile()
        try {
            val source = File(dir, "source.pdf"); val out = File(dir, "out.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                page.annotations = listOf(
                    PDAnnotationText().apply { rectangle = PDRectangle(20f, 20f, 30f, 30f); contents = "locked"; isLocked = true },
                    PDAnnotationText().apply { rectangle = PDRectangle(80f, 20f, 30f, 30f); contents = "same" },
                    PDAnnotationText().apply { rectangle = PDRectangle(80f, 20f, 30f, 30f); contents = "same" }
                )
                doc.save(source)
            }
            val entries = PdfAnnotationEditor.list(source)
            assertTrue(entries.none { it.editable })
            assertThrows(IllegalArgumentException::class.java) { PdfAnnotationEditor.apply(source, out, PdfAnnotationChange(entries[0].key, 0, true, "")) }
            assertThrows(IllegalArgumentException::class.java) { PdfAnnotationEditor.apply(source, out, PdfAnnotationChange(entries[1].key, 0, true, "")) }
            assertThrows(IllegalArgumentException::class.java) { PdfAnnotationEditor.apply(source, out, PdfAnnotationChange(entries[1].key, 0, false, "x".repeat(1001))) }
            assertFalse(out.exists()); assertFalse(dir.listFiles()!!.any { it.extension == "part" })
        } finally { dir.deleteRecursively() }
    }
}
