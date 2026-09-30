package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfPageGeometryTest {
    @Test fun cornersIncludeCropOffsetAndEveryRotation() {
        val page = PDPage(PDRectangle(600f,800f)).apply { cropBox = PDRectangle(50f,100f,400f,600f) }
        val expected = mapOf(0 to (50f to 700f),90 to (50f to 100f),180 to (450f to 100f),270 to (450f to 700f))
        expected.forEach { (rotation,corner) ->
            page.rotation = rotation
            val geometry = PdfPageGeometry(page)
            assertEquals(corner,geometry.point(0f,0f))
            assertEquals(if (rotation % 180 == 0) 400f else 600f,geometry.width)
            assertEquals(250f to 400f,geometry.point(.5f,.5f))
        }
    }
    @Test fun rotatedCroppedMarkupIsPlacedWhereThePreviewSelected() {
        val dir = kotlin.io.path.createTempDirectory("pdf-rotation-").toFile()
        try {
            val source = File(dir,"source.pdf"); val output = File(dir,"marked.pdf")
            PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(600f,800f)).apply {
                cropBox = PDRectangle(50f,100f,400f,600f); rotation = 90
            }); doc.save(source) }
            assertTrue(PdfOfficeTools.addMarkup(source,output,0,PdfMarkup.HIGHLIGHT,.1f,.2f,.3f,.1f))
            PDDocument.load(output).use { doc ->
                val annotation = doc.getPage(0).annotations.single() as PDAnnotationTextMarkup
                assertArrayEquals(floatArrayOf(130f,160f,130f,340f,170f,160f,170f,340f), annotation.quadPoints, .001f)
                assertEquals(90,doc.getPage(0).rotation)
                assertEquals(50f,doc.getPage(0).cropBox.lowerLeftX,.001f)
            }
        } finally { dir.deleteRecursively() }
    }
}
