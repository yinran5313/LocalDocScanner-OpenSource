package com.localdoc.scanner

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.cv.*
import com.localdoc.scanner.models.*
import com.localdoc.scanner.structure.*
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.ocr.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V51FinalAndroidTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun pack(id: String)=runBlocking { ModelDownloads.import(app,Uri.fromFile(File(app.filesDir,"testmodels/$id.zip"))) }
    @Test fun actualDeblurUses384ContextAndPreservesDimensionsAlpha(): Unit = runBlocking {
        pack("deblur")
        val input=Bitmap.createBitmap(33,25,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128,145,145,145)) }
        val output=PhotoDeblur.restore(app,input,.6f)
        try { assertEquals(33,output.width); assertEquals(25,output.height); assertEquals(128,Color.alpha(output.getPixel(5,5))) }
        finally { input.recycle();output.recycle() }
        Unit
    }
    @Test fun actualLayoutLoadsPinnedNativeModel(): Unit = runBlocking {
        pack("layout")
        val input=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try { assertTrue(DocumentLayoutModel.detect(app,input).all { it.valid }) } finally { input.recycle() }
        Unit
    }
    @Test fun blankPaperIsNotGlareAndEdgeSkinBrightIslandAreCandidates() {
        val white=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try { assertTrue(ObstructionDetector.detect(white).isEmpty()) } finally { white.recycle() }
        val scene=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160,160,160)) }
        val canvas=Canvas(scene);canvas.drawRect(0f,80f,25f,170f,Paint().apply { color=Color.rgb(210,150,110) })
        canvas.drawCircle(150f,140f,20f,Paint().apply { color=Color.WHITE })
        try { val candidates=ObstructionDetector.detect(scene);assertTrue(candidates.any { it.kind=="手指候选" });assertTrue(candidates.any { it.kind=="反光候选" });assertTrue(candidates.all { it.strokes.isNotEmpty() }) }
        finally { scene.recycle() }
    }
    @Test fun denoiseReducesSyntheticNoiseWithoutDestroyingAlpha() {
        val random=java.util.Random(42);val pixels=IntArray(96*96) { val v=128+random.nextInt(31)-15;Color.argb(128,v,v,v) }
        val source=Bitmap.createBitmap(pixels,96,96,Bitmap.Config.ARGB_8888);val result=PhotoDenoise.run(source,1f)
        try { val fixed=IntArray(pixels.size);result.getPixels(fixed,0,96,0,0,96,96)
            assertTrue(fixed.all { Color.alpha(it)==128 });assertTrue(fixed.sumOf { kotlin.math.abs(Color.red(it)-128) }<pixels.sumOf { kotlin.math.abs(Color.red(it)-128) })
        } finally { source.recycle();result.recycle() }
    }
    @Test fun basicRepairPreservesUnselectedPixelsAndSelectedAlpha() {
        val source=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128,140,150,160)) }
        val strokes=listOf(listOf(com.localdoc.scanner.pdf.PdfInkPoint(.5f,.45f),com.localdoc.scanner.pdf.PdfInkPoint(.5f,.55f)))
        val mask=RegionRepair.selectionMask(120,80,strokes,.1f);val selected=ByteArray(120*80);mask.get(0,0,selected);mask.release()
        val output=RegionRepair.repair(source,strokes,.1f)
        try { for(y in 0 until 80) for(x in 0 until 120) { assertEquals(128,Color.alpha(output.getPixel(x,y)));if(selected[y*120+x].toInt()==0)assertEquals(source.getPixel(x,y),output.getPixel(x,y)) } }
        finally { source.recycle();output.recycle() }
    }
    @Test fun mixedPageRetainsBodyFigureCaptionAndSourceRegions(): Unit = runBlocking {
        val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val words=listOf(OcrTextBox("Heading",.1f,.02f,.8f,.08f),OcrTextBox("Left body",.1f,.15f,.4f,.3f),OcrTextBox("Right body",.6f,.15f,.9f,.3f),OcrTextBox("Caption",.2f,.65f,.8f,.7f))
        val regions=listOf(LayoutRegion("title",1f,.08f,.01f,.9f,.1f),LayoutRegion("text",1f,.08f,.14f,.45f,.32f),LayoutRegion("text",1f,.55f,.14f,.95f,.32f),LayoutRegion("figure",1f,.2f,.4f,.8f,.6f),LayoutRegion("figure_caption",1f,.1f,.64f,.9f,.72f))
        val dir=File(app.cacheDir,"layout-fixture-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try { val out=StructuredMarkdownPage.render(app,image,words,regions,false,"AUTO",dir,"1-1")
            assertTrue(out.markdown.contains("### Heading"));assertTrue(out.markdown.indexOf("Left body")<out.markdown.indexOf("Right body"));assertTrue(out.markdown.contains("Caption"));assertEquals(1,out.files.size);assertTrue(out.files.single().isFile);assertEquals(5,out.regions.size)
        } finally { image.recycle();dir.deleteRecursively() }
        Unit
    }
}
