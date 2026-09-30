package com.localdoc.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android rendering is necessary: JVM stubs cannot validate glyph visibility. */
@RunWith(AndroidJUnit4::class)
class PdfFormAppearanceInstrumentedTest {
    @Test fun chineseValueHasVisibleGlyphsAfterSaveAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val dir = File(context.cacheDir,"form-appearance-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source=File(dir,"source.pdf"); val output=File(dir,"filled.pdf")
            PDDocument().use { document ->
                val page=PDPage(PDRectangle.A4); document.addPage(page)
                val form=PDAcroForm(document); document.documentCatalog.acroForm=form
                val oldFont=context.assets.open("fonts/LXGWWenKai-Regular.ttf").use { PDType0Font.load(document,it,true) }
                form.defaultResources=PDResources().apply { put(COSName.getPDFName("Old"),oldFont) }
                form.defaultAppearance="/Old 12 Tf 0 g"
                val field=PDTextField(form).apply { partialName="name" }
                field.widgets.first().apply { rectangle=PDRectangle(40f,720f,300f,35f); this.page=page; page.annotations.add(this) }
                form.fields=listOf(field); document.save(source)
            }
            assertTrue(PdfOfficeTools.fillForm(context,source,output,mapOf("name" to "测试值")))
            PDDocument.load(output).use { document -> assertEquals("测试值",document.documentCatalog.acroForm.getField("name").valueAsString) }
            ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var dark=0
                            for (y in page.height-755 until page.height-720) for (x in 40 until 340) {
                                val c=bitmap.getPixel(x,y)
                                if (Color.red(c)<180 && Color.green(c)<180 && Color.blue(c)<180) dark++
                            }
                            assertTrue("Saved form appearance must render Chinese glyphs",dark>100)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
