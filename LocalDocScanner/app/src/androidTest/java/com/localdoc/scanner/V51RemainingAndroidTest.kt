package com.localdoc.scanner

import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.cv.RegionRepair
import com.localdoc.scanner.data.*
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.pdf.PdfInkPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(AndroidJUnit4::class)
class V51RemainingAndroidTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun everyOptionalPackImportsWithHashesAndRunsOnAndroid(): Unit = runBlocking {
        for (pack in ModelCatalog.all(app)) {
            val archive = File(app.filesDir,"testmodels/${pack.id}.zip")
            assertTrue("Missing test fixture: ${pack.id}",archive.isFile)
            val installed = ModelDownloads.import(app,Uri.fromFile(archive))
            assertEquals(pack.id,installed.id); assertTrue(ModelCatalog.installed(app,pack))
        }
        assertTrue(LocalTranslation.translate(app,"你好，请打开这份文件。",true).lowercase().contains("open"))
        assertTrue(LocalTranslation.translate(app,"Please open this document.",false).contains("打开"))
        val source = Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888)
        Canvas(source).apply { drawColor(Color.WHITE); drawText("TABLE",10f,40f,Paint().apply { color=Color.BLACK; textSize=16f }) }
        try {
            ModelInference.portrait(app,source,Color.BLUE).let { assertEquals(source.width,it.width); it.recycle() }
            ModelInference.unwarp(app,source).let { assertTrue(it.width>0 && it.height>0); it.recycle() }
            TableModel.recover(app,source,listOf(OcrTextBox("TABLE",.1f,.1f,.8f,.4f))) // May reject a non-table, but should not be passed a fabricated table.
            val counts = ObjectCounting.detect(app,source,0); assertTrue(counts.all { it.left in 0f..1f && it.right in 0f..1f })
            PhotoRestoration.restore(app,source,2).let { assertEquals(192,it.width); assertEquals(256,it.height); it.recycle() }
        } finally { source.recycle() }
        val fixture = File(app.filesDir,"testmodels/formula.png"); val bitmap = requireNotNull(com.localdoc.scanner.util.ImageIo.loadFromFile(fixture,1600))
        try { val latex = FormulaRecognition.recognize(app,bitmap); assertTrue(latex,latex.contains("\\frac") && !latex.contains('\u0120')); assertTrue(latex,latex.replace(" ","").endsWith("=1")) } finally { bitmap.recycle() }
        val repo=DocRepository(app); val invoice=repo.createDoc("测试采购合同"); val recipe=repo.createDoc("测试烹饪")
        try {
            repo.setOcrText(invoice,"供应商合同采购货物，发票价税合计113元，结算付款条款。")
            repo.setOcrText(recipe,"鸡蛋炒饭的做法，锅中放油加入鸡蛋和米饭。")
            val scores=SemanticSearch.search(app,"购买商品的发票和付款规定").associate { it.first.key to it.second }
            assertTrue(scores["scan:$invoice"]!! > scores["scan:$recipe"]!!)
        } finally { repo.deleteForever(invoice); repo.deleteForever(recipe) }
        Unit
    }
    @Test fun generalOcrStillUsesBundledMediumForLegacyStandardRequests(): Unit = runBlocking {
        val bitmap=Bitmap.createBitmap(1100,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(bitmap).drawText("HELLO 12345",30f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=120f; typeface=Typeface.DEFAULT })
        val engine=com.localdoc.scanner.ocr.PaddleOcrEngine(app)
        try {
            val result=engine.recognize(bitmap,false)
            assertTrue(engine.label.contains("medium")); assertTrue(result.text,result.text.replace(" ","").contains("12345"))
            assertTrue(result.boxes.isNotEmpty()); assertTrue(result.boxes.all(com.localdoc.scanner.ocr.OcrLayout::valid))
        } finally { bitmap.recycle(); engine.close() }
        Unit
    }
    @Test fun projectionPresetKeepsMonochromeTextAndDimensions() {
        val source=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).drawRect(20f,20f,80f,50f,Paint().apply { color=Color.BLACK })
        val result=requireNotNull(com.localdoc.scanner.cv.ProjectionEnhancement.apply(source))
        try { assertEquals(120,result.width); assertEquals(80,result.height); assertTrue(Color.red(result.getPixel(40,35))<5); assertTrue(Color.red(result.getPixel(110,70))>250) }
        finally { source.recycle(); result.recycle() }
    }
    @Test fun smallRegionRepairPreservesEveryPixelOutsideTheMask() {
        val source=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888); source.eraseColor(Color.WHITE)
        Canvas(source).drawLine(76f,55f,84f,65f,Paint().apply { color=Color.BLACK; strokeWidth=3f })
        val repaired=RegionRepair.repair(source,listOf(listOf(PdfInkPoint(.47f,.44f),PdfInkPoint(.54f,.56f))),.06f)
        try { for (y in 0 until 120) for (x in 0 until 160) if (x !in 66..94 || y !in 43..77) assertEquals("$x,$y",source.getPixel(x,y),repaired.getPixel(x,y)); assertTrue(Color.red(repaired.getPixel(80,60))>200) }
        finally { source.recycle(); repaired.recycle() }
    }
    @Test fun folderAndAtomicBatchChangesKeepDocumentsAndRejectPartialUpdates(): Unit = runBlocking {
        val repo=DocRepository(app); val a=repo.createDoc("测试批量A"); val b=repo.createDoc("测试批量B"); val folder="测试-${System.nanoTime()}"; val renamed=folder+"改名"
        try {
            repo.batchDocuments(listOf(a,b),"folder",folder)
            repo.changeFolder(folder,renamed)
            assertEquals(renamed,repo.getItem(a)!!.folder); assertEquals(renamed,repo.getItem(b)!!.folder)
            runCatching { repo.batchDocuments(listOf(a,"missing-doc"),"folder","不可部分应用") }.onSuccess { fail("must reject") }
            assertEquals(renamed,repo.getItem(a)!!.folder)
            repo.changeFolder(renamed,null); assertNull(repo.getItem(a)!!.folder); assertNotNull(repo.getItem(b))
        } finally { repo.deleteForever(a); repo.deleteForever(b) }
        Unit
    }
}
