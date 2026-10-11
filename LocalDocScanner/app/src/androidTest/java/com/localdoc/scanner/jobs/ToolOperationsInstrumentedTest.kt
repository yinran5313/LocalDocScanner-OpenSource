package com.localdoc.scanner.jobs

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.pdf.PdfReadSession
import com.localdoc.scanner.util.ImageIo
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercise actual task execution, checkpoints and emitted files, beyond UI entry existence. */
class ToolOperationsInstrumentedTest {
    @Test fun pdfAndImageToolsEmitReadableResultsAndKeepSourceFiles() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val root=File(context.cacheDir,"operations-${UUID.randomUUID()}").apply { mkdirs() }
        val source=File(root,"source.pdf")
        PDDocument().use { doc ->
            repeat(3) { i ->
                val page=PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc,page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA,22f); stream.newLineAtOffset(40f,700f); stream.showText("SHIYE 501 PAGE ${i+1}"); stream.endText()
                }
            }; doc.save(source)
        }
        val image=File(root,"image.jpg")
        val bitmap=Bitmap.createBitmap(360,480,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("SHIYE 501",20f,160f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=36f }) }
        assertTrue(ImageIo.saveJpeg(bitmap,image));bitmap.recycle()
        val sourceHash=ToolCheckpoints.hash(source);val imageHash=ToolCheckpoints.hash(image)
        val tasks=mutableListOf<String>()
        suspend fun run(tool:String,files:List<File>,parameters:Map<String,String> = emptyMap(),secret:CharArray?=null):FlowOutcome {
            val id=UUID.randomUUID().toString();tasks+=id
            val request=ToolRequest(TOOL_ENTRIES.first { it.id==tool },files,files.map { it.name })
            return ToolTaskProcessor(context,ToolTaskSpec(id,request,parameters,files.associate { it.absolutePath to ToolCheckpoints.hash(it) }),secret) { _,_,_-> }.run().also { outcome ->
                assertTrue("$tool must emit files",outcome.files.isNotEmpty())
                outcome.files.forEach { assertTrue("$tool output empty/missing: ${it.name}",it.isFile && it.length()>0) }
            }
        }
        fun pages(file:File,password:String=""):Int = PdfReadSession.open(file,password).use { it.pageCount }
        try {
            assertEquals(6,pages(run("pdf_merge",listOf(source,source.copyTo(File(root,"copy.pdf")))).files.single()))
            val split=run("pdf_split",listOf(source));assertEquals(3,split.files.size);split.files.forEach { assertEquals(1,pages(it)) }
            val compressed=run("pdf_compress",listOf(source)).files.single()
            assertEquals(3,pages(compressed));PdfReadSession.open(compressed).use { assertTrue(it.pageText(1).contains("PAGE 2")) }
            assertEquals(3,run("pdf_to_images",listOf(source)).files.size)
            assertTrue(run("pdf_text",listOf(source)).files.single().readText().contains("PAGE 3"))
            val encrypted=run("pdf_encrypt",listOf(source),secret="audit501".toCharArray()).files.single()
            assertEquals(3,pages(encrypted,"audit501"))
            assertEquals(2,pages(run("images_to_pdf",listOf(image,image)).files.single()))
            val longImage=run("long_image",listOf(image,image)).files.single()
            ImageIo.loadFromFile(longImage,1600)!!.let { assertTrue(it.height>it.width);it.recycle() }
            run("image_edit",listOf(image)).docFiles.forEach { output -> ImageIo.loadFromFile(output,600)!!.recycle() }
            val compare=run("pdf_compare",listOf(source,source));assertTrue(compare.files.any { it.extension=="pdf" });assertTrue(compare.summary.any { it.second=="0" })
            assertEquals(sourceHash,ToolCheckpoints.hash(source));assertEquals(imageHash,ToolCheckpoints.hash(image))
        } finally { tasks.forEach { ToolTasks.dir(context,it).deleteRecursively() };root.deleteRecursively() }
    }
}
