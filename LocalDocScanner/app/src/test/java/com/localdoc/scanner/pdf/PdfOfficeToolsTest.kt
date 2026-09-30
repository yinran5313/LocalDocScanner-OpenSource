package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PdfOfficeToolsTest {
    @Test fun inkIsEditableAnnotationWithAppearanceAndLeavesPageContentAlone() {
        val dir = kotlin.io.path.createTempDirectory("ink-pdf-").toFile()
        val source = File(dir, "source.pdf"); val output = File(dir, "ink.pdf")
        PDDocument().use { doc -> doc.addPage(PDPage()); doc.save(source) }
        assertTrue(PdfOfficeTools.drawInk(source, output, 0,
            listOf(listOf(PdfInkPoint(.1f, .2f), PdfInkPoint(.8f, .7f))), .1f, .1f, .5f, .2f))
        PDDocument.load(output).use { doc ->
            val annotation = doc.getPage(0).annotations.single()
            assertEquals("Ink", annotation.subtype)
            assertTrue(annotation.appearance.normalAppearance.appearanceStream.cosObject.length > 0)
        }
        dir.deleteRecursively()
    }
    @Test
    fun markupCreatesCopyAndLeavesSourceUnchanged() {
        val dir = kotlin.io.path.createTempDirectory("pdf-office-").toFile()
        val source = File(dir, "source.pdf")
        val output = File(dir, "marked.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(source)
        }
        val before = sha256(source)

        assertTrue(PdfOfficeTools.addMarkup(source, output, 0, PdfMarkup.HIGHLIGHT, 0.1f, 0.1f, 0.4f, 0.08f))
        assertEquals(before, sha256(source))
        PDDocument.load(output).use { document ->
            assertEquals(1, document.getPage(0).annotations.size)
        }
        dir.deleteRecursively()
    }

    @Test
    fun sequentialEditsRemainInTheFinalPdf() {
        val dir = kotlin.io.path.createTempDirectory("pdf-multi-edit-").toFile()
        val source = File(dir, "source.pdf")
        val intermediate = File(dir, "step-1.pdf")
        val output = File(dir, "final.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(source)
        }

        assertTrue(PdfOfficeTools.addMarkup(source, intermediate, 0, PdfMarkup.UNDERLINE, 0.12f, 0.2f, 0.45f, 0.04f))
        assertTrue(PdfOfficeTools.addNote(intermediate, output, 0, "复核这里", 0.72f, 0.25f))
        PDDocument.load(output).use { document ->
            assertEquals(2, document.getPage(0).annotations.size)
        }
        dir.deleteRecursively()
    }

    @Test
    fun fillsExistingAcroFormFieldInCopy() {
        val dir = kotlin.io.path.createTempDirectory("pdf-form-").toFile()
        val source = File(dir, "source.pdf")
        val output = File(dir, "filled.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val font = PDType0Font.load(document, File("src/main/assets/fonts/LXGWWenKai-Regular.ttf"))
            form.defaultResources = PDResources().apply { put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Noto"), font) }
            form.defaultAppearance = "/Noto 10 Tf 0 g"
            val field = PDTextField(form).apply { partialName = "name" }
            field.widgets.first().apply {
                rectangle = PDRectangle(50f, 700f, 200f, 30f)
                this.page = page
                page.annotations.add(this)
            }
            form.fields = listOf(field)
            document.save(source)
        }

        File("src/main/assets/fonts/LXGWWenKai-Regular.ttf").inputStream().use { fontInput ->
            assertTrue(PdfOfficeTools.fillForm(source, output, mapOf("name" to "测试值"), fontInput))
        }
        PDDocument.load(output).use { document ->
            val form = document.documentCatalog.acroForm
            val field = form.getField("name")
            assertEquals("测试值", field.valueAsString)
            assertTrue(field.widgets.first().appearance.normalAppearance.appearanceStream.cosObject.length > 0)
            assertTrue(!form.needAppearances)
        }
        dir.deleteRecursively()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
