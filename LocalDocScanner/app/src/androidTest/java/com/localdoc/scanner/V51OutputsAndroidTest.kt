package com.localdoc.scanner

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.export.*
import com.localdoc.scanner.structure.CardTemplates
import com.localdoc.scanner.util.ImageIo
import com.localdoc.scanner.pdf.PdfTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

@RunWith(AndroidJUnit4::class)
class V51OutputsAndroidTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun zipKeepsGoodEntriesSourceDirectoriesAndCheckpointRecovery(): Unit = runBlocking {
        val zip=File(app.cacheDir,"zip-flow-proof.zip")
        ZipOutputStream(zip.outputStream()).use { out -> for ((name,body) in listOf("课程/语文.txt" to "测试甲","课程/数学.txt" to "测试乙","C:/outside.txt" to "不应写出")) { out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray()); out.closeEntry() } }
        val spec=ToolTaskSpec(java.util.UUID.randomUUID().toString(),ToolRequest(TOOL_ENTRIES.first { it.id=="zip_import" },listOf(zip),listOf(zip.name)),emptyMap(),mapOf(zip.absolutePath to ToolCheckpoints.hash(zip)))
        try {
            val first=ToolTaskProcessor(app,spec,null) { _,_,_ -> }.run()
            assertEquals(3,first.files.size); assertEquals(1,first.failures.size)
            assertTrue(first.fileFolders.values.any { it.endsWith("/课程") }); assertTrue(first.fileLabels.values.contains("语文.txt"))
            val stamps=first.files.associate { it.absolutePath to it.lastModified() }
            val resumed=ToolTaskProcessor(app,spec,null) { _,_,_ -> }.run()
            assertEquals(stamps,resumed.files.associate { it.absolutePath to it.lastModified() })
            assertFalse(File(ToolTasks.dir(app,spec.id),"outside.txt").exists())
        } finally { zip.delete() }
        Unit
    }
    @Test fun actualSizeCardSheetAndMultipageMarkdownPdfRenderOnAndroid() {
        val image=File(app.cacheDir,"card-proof.png"); val card=File(app.cacheDir,"card-proof.pdf"); val md=File(app.cacheDir,"markdown-proof.pdf")
        val bitmap=Bitmap.createBitmap(856,540,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(bitmap).drawRect(0f,0f,856f,540f,Paint().apply { color=Color.rgb(20,170,160) })
        try {
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            CardSheet.write(listOf(image,image),CardTemplates.templates.first(),card)
            assertEquals(1,PdfTools.pageCount(card))
            val rendered=PdfTools.renderPage(card,0,595)!!
            try { assertTrue(Color.green(rendered.getPixel(180,90))>120) } finally { rendered.recycle() }
            MarkdownPdf.write(app,"# 标题\n\n| 项目 | 数量 |\n| --- | --- |\n| 纸张 | 20 |\n\n"+"这是分页测试段落，保留文字和表格。\n\n".repeat(160),md)
            assertTrue(PdfTools.pageCount(md)>1)
            PdfTools.renderPage(md,0,595)!!.also { check(ImageIo.saveJpeg(it,File(app.filesDir,"testmodels/markdown-preview.jpg"))); it.recycle() }
            assertTrue(md.length()>0)
        } finally { bitmap.recycle(); image.delete(); card.delete(); md.delete() }
    }
}
