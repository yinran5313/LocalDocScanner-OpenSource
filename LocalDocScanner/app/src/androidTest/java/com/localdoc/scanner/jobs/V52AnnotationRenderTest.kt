package com.localdoc.scanner.jobs

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class V52AnnotationRenderTest {
    @Test fun movedHighlightRendersAtNewPositionOnRotatedCroppedPdf() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val dir=File(app.cacheDir,"annotation-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(dir,"source.pdf");val marked=File(dir,"marked.pdf");val moved=File(dir,"moved.pdf")
            PDDocument().use {doc ->doc.addPage(PDPage(PDRectangle(600f,800f)).apply {cropBox=PDRectangle(50f,100f,400f,600f);rotation=90});doc.save(source)}
            assertTrue(PdfOfficeTools.addMarkup(source,marked,0,PdfMarkup.HIGHLIGHT,.1f,.2f,.3f,.1f))
            val original=marked.readBytes();val entry=PdfAnnotationEditor.list(marked).single()
            assertTrue(PdfAnnotationEditor.apply(marked,moved,PdfAnnotationChange(entry.key,0,false,"moved",NormalizedRect(.5f,.5f,.2f,.2f))))
            val bitmap=PdfTools.renderPage(moved,0,800)!!
            try {
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                var count=0;var x=0.0;var y=0.0
                pixels.forEachIndexed {i,c ->if(Color.red(c)>180 && Color.green(c)>170 && Color.blue(c)<230) {count++;x+=i%bitmap.width;y+=i/bitmap.width}}
                File(app.filesDir,"v52-proof").mkdirs()
                File(app.filesDir,"v52-proof/moved-highlight.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                moved.copyTo(File(app.filesDir,"v52-proof/moved-highlight.pdf"),overwrite=true)
                marked.copyTo(File(app.filesDir,"v52-proof/original-highlight.pdf"),overwrite=true)
                assertTrue("Moved highlight must remain visible",count>100)
                assertEquals(.6,x/count/bitmap.width,.04);assertEquals(.6,y/count/bitmap.height,.04)
                File(app.filesDir,"v52-proof").mkdirs()
                File(app.filesDir,"v52-proof/moved-highlight.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            } finally {bitmap.recycle()}
            PdfReadSession.open(moved).use { session ->
                val compatible=session.renderCompatible(0,800)
                try {
                    val color=compatible.getPixel((compatible.width*.6).toInt(),(compatible.height*.6).toInt())
                    assertTrue("Compatibility renderer must retain highlight",Color.red(color)>180 && Color.green(color)>170 && Color.blue(color)<230)
                } finally {compatible.recycle()}
            }
            val exported=PdfTools.toImages(moved,File(dir,"images"),150).single()
            val exportedBitmap=android.graphics.BitmapFactory.decodeFile(exported.absolutePath)
            try {
                val color=exportedBitmap.getPixel((exportedBitmap.width*.6).toInt(),(exportedBitmap.height*.6).toInt())
                assertTrue("Export must retain highlight",Color.red(color)>180 && Color.green(color)>170 && Color.blue(color)<230)
            } finally {exportedBitmap.recycle()}
            assertArrayEquals(original,marked.readBytes())
        } finally {dir.deleteRecursively()}
    }
}
