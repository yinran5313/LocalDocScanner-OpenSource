package com.localdoc.scanner

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.models.*
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.*
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.pdf.*
import com.localdoc.scanner.util.ImageIo
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import java.util.zip.*

class V51Dev3AndroidTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun spec(tool: String, files: List<File>, params: Map<String,String> = emptyMap()) = ToolTaskSpec(UUID.randomUUID().toString(),
        ToolRequest(TOOL_ENTRIES.first { it.id==tool },files,files.map { it.name }),params,files.associate { it.absolutePath to ToolCheckpoints.hash(it) })
    private fun pack(id: String) = runBlocking {
        val archive=File(app.filesDir,"testmodels/$id.zip"); assertTrue(archive.isFile)
        ModelDownloads.import(app,Uri.fromFile(archive))
    }
    private fun fake(text: String, count: () -> Unit = {}) = object : OcrEngine {
        override val available=true; override val label="test"
        override suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome { count(); return OcrOutcome(text,1,1,0,listOf(OcrTextBox(text,.1f,.15f,.85f,.25f))) }
    }

    @Test fun pickerKeepsOriginalResolutionAlphaAndBytesWhileRejectingBadImages(): Unit = runBlocking {
        val source=File(app.cacheDir,"picker-alpha.png"); val invalid=File(app.cacheDir,"picker-invalid.txt").apply { writeText("bad image") }
        val bitmap=Bitmap.createBitmap(4001,2,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128,120,130,140)) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val vm=com.localdoc.scanner.ui.AppViewModel(app.applicationContext as android.app.Application)
        try {
            withContext(Dispatchers.Main) { vm.onFilesPicked(TOOL_ENTRIES.first { it.id=="photo_restore" },listOf(source,invalid).map { androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",it) }) }
            withTimeout(15000) { while(vm.toolRequest==null)delay(50) }
            val input=vm.toolRequest!!.files.single();assertEquals("png",input.extension);assertEquals(ToolCheckpoints.hash(source),ToolCheckpoints.hash(input))
            val loaded=BitmapFactory.decodeFile(input.absolutePath)
            try { assertEquals(4001,loaded.width);assertEquals(128,Color.alpha(loaded.getPixel(100,0))) } finally { loaded.recycle() }
            input.delete()
        } finally { withContext(Dispatchers.Main) { vm.closeTool() };source.delete();invalid.delete() }
        Unit
    }

    @Test fun restoreResumesTilesWithoutRepeatingCompletedInferenceAndRetainsAlpha(): Unit = runBlocking {
        pack("restore")
        val source=Bitmap.createBitmap(321,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128,240,240,240)) }
        val input=File(app.cacheDir,"sr-resume.png"); input.outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }; source.recycle()
        val job=spec("photo_restore",listOf(input),mapOf("restoreScale" to "2"))
        try {
            try { ToolTaskProcessor(app,job,null) { done,_,label -> if(done==1 && label.startsWith("高清图块")) throw CancellationException("test pause") }.run(); fail("must pause") }
            catch(_: CancellationException) { }
            val root=ToolTasks.dir(app,job.id); val journal=ToolCheckpoints(root,ToolTasks.gson)
            val receipts=File(root,"units").listFiles()!!.map { ToolTasks.gson.fromJson(it.readText(),ToolUnitReceipt::class.java) }
            val tile=receipts.single { it.key.startsWith("sr-tile:") }; val stamp=File(tile.files.single().path).lastModified()
            assertNotNull(journal.load(tile.key)); assertNull(journal.load("restore"))
            assertTrue(ToolTasks.recoveredOutcome(app,job.id).files.isEmpty())
            val result=ToolTaskProcessor(app,job,null) { _,_,_ -> }.run()
            assertEquals(stamp,File(tile.files.single().path).lastModified())
            val restored=BitmapFactory.decodeFile(result.files.single().absolutePath)
            try { assertEquals(642,restored.width); assertEquals(240,restored.height); assertEquals(128,Color.alpha(restored.getPixel(100,100))) } finally { restored.recycle() }
            assertEquals(3,File(root,"tiles").listFiles()!!.size)
        } finally { ToolTasks.dir(app,job.id).deleteRecursively(); input.delete() }
        Unit
    }

    @Test fun learnedEraseRunsAndroidOnnxAndChangesOnlyTheSelectedRegion(): Unit = runBlocking {
        pack("inpaint")
        val source=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(175,180,185)) }
        Canvas(source).drawRect(110f,68f,130f,92f,Paint().apply { color=Color.BLACK })
        val strokes=listOf(listOf(PdfInkPoint(.5f,.43f),PdfInkPoint(.5f,.57f)))
        val mask=com.localdoc.scanner.cv.RegionRepair.selectionMask(240,160,strokes,.15f)
        val selected=ByteArray(240*160); mask.get(0,0,selected); mask.release()
        val result=LearnedRegionRepair.repair(app,source,strokes,.15f)
        try {
            for(y in 0 until 160) for(x in 0 until 240) if(selected[y*240+x].toInt()==0) assertEquals("$x,$y",source.getPixel(x,y),result.getPixel(x,y))
            assertTrue(Color.red(result.getPixel(120,80))>90)
            val out=File(app.filesDir,"testmodels/lama-preview.png"); out.outputStream().use { result.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { result.recycle(); source.recycle() }
        Unit
    }

    @Test fun pdfEnhancementPreservesVisibleSizeAndAddsCheckpointedSearchText(): Unit = runBlocking {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val input=File(app.cacheDir,"enhance-proof.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle(595f,842f))); d.addPage(PDPage(PDRectangle(500f,700f)).apply { cropBox=PDRectangle(40f,60f,400f,600f); rotation=90 }); d.save(input) }
        val hash=ToolCheckpoints.hash(input)
        val job=spec("pdf_enhance",listOf(input),mapOf("selectedPages" to "2","pdfEnhanceSearchable" to "true"))
        try {
            var calls=0
            val output=ToolTaskProcessor(app,job,null) { _,_,_ -> }.apply { recognizerForTest=fake("复核测试123") { calls++ } }.run().files.single()
            assertEquals(1,calls)
            PDDocument.load(output).use { d -> assertEquals(1,d.numberOfPages); assertEquals(600f,d.getPage(0).mediaBox.width,.01f); assertEquals(400f,d.getPage(0).mediaBox.height,.01f); assertTrue(PDFTextStripper().getText(d).contains("复核测试123")) }
            ToolTaskProcessor(app,job,null) { _,_,_ -> }.apply { recognizerForTest=fake("不应重跑") { fail("OCR should reuse receipt") } }.run()
            assertEquals(hash,ToolCheckpoints.hash(input))
        } finally { input.delete(); ToolTasks.dir(app,job.id).deleteRecursively() }
        Unit
    }

    @Test fun markdownBundleContainsRelativeImagesAndReusesOcr(): Unit = runBlocking {
        val input=File(app.cacheDir,"markdown-source.png"); val bitmap=Bitmap.createBitmap(200,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val job=spec("structured_markdown",listOf(input))
        try {
            val result=ToolTaskProcessor(app,job,null) { _,_,_ -> }.apply { recognizerForTest=fake("原页内容") }.run()
            val markdown=result.files.first { it.extension=="md" }.readText()
            assertTrue(markdown.contains("![第1页原图](source-1-1.jpg)"))
            val bundle=result.files.first { it.extension=="zip" }
            assertEquals(listOf(bundle),result.deliverables);assertEquals(2,result.libraryOutputs.size)
            ZipFile(bundle).use { zip -> assertNotNull(zip.getEntry("source-1-1.jpg")); assertNotNull(zip.getEntry("来源1-1.json")); assertTrue(zip.getInputStream(zip.getEntry("识别整理.md")).bufferedReader().readText().contains("原页内容")) }
            val stamp=bundle.lastModified()
            ToolTaskProcessor(app,job,null) { _,_,_ -> }.apply { recognizerForTest=fake("不要重新识别") { fail("must reuse OCR") } }.run()
            assertEquals(stamp,bundle.lastModified())
        } finally { input.delete(); ToolTasks.dir(app,job.id).deleteRecursively() }
        Unit
    }

    @Test fun actualPdfSuperResolutionUsesTilesAndKeepsPhysicalPageSize(): Unit = runBlocking {
        pack("restore"); com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val input=File(app.cacheDir,"pdf-sr-proof.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle(200f,120f)));d.save(input) }
        val job=spec("pdf_enhance",listOf(input),mapOf("pdfSuperResolution" to "true","pdfEnhanceSide" to "800"))
        try {
            val result=ToolTaskProcessor(app,job,null) { _,_,_ -> }.run()
            PDDocument.load(result.files.single()).use { d ->
                assertEquals(200f,d.getPage(0).mediaBox.width,.01f);assertEquals(120f,d.getPage(0).mediaBox.height,.01f)
                val resources=d.getPage(0).resources
                val image=resources.getXObject(resources.xObjectNames.first()) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                // Existing renderer caps small physical pages at 3 pixels per point.
                assertEquals(1200,image.width);assertEquals(720,image.height)
            }
            assertEquals(12,File(ToolTasks.dir(app,job.id),"tiles").listFiles()!!.size)
        } finally { input.delete();ToolTasks.dir(app,job.id).deleteRecursively() }
        Unit
    }

    @Test fun markdownImagesAndLocationsSurviveLibraryImportBackupAndPdfExport(): Unit = runBlocking {
        val repo=DocRepository(app);val before=repo.observeDocs().first().map { it.id }.toSet()
        val folder=File(app.cacheDir,"md-portable-${System.nanoTime()}").apply { mkdirs() }
        val image=File(folder,"source-1-1.jpg");val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        ImageIo.saveJpeg(bitmap,image);bitmap.recycle()
        val location=File(folder,"来源1-1.json").apply { writeText("{\"page\":1,\"boxes\":[]}") }
        val markdown=File(folder,"with-images.md").apply { writeText("# 内容\n\n![页](source-1-1.jpg)") }
        val backup=File(app.cacheDir,"markdown-v2-backup.zip")
        try {
            val uri=androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",markdown)
            assertEquals(markdown.canonicalFile,com.localdoc.scanner.export.MarkdownLocalAssets.ownedSource(app,uri))
            val id=repo.importFile(uri,"with-images.md","text/markdown")
            val owned=repo.ownedFile(id)!!
            assertEquals(2,com.localdoc.scanner.export.MarkdownLocalAssets.documentFiles(owned).size)
            image.delete();location.delete();markdown.delete()
            val saved=repo.backupLibrary(Uri.fromFile(backup));assertTrue(saved.error,saved.success)
            val recovered=repo.restoreLibrary(Uri.fromFile(backup));assertTrue(recovered.error,recovered.success)
            val restored=repo.observeDocs().first().first { it.id!=id && it.title.startsWith("with-images") }
            val recoveredFile=repo.ownedFile(restored.id)!!
            assertEquals(2,com.localdoc.scanner.export.MarkdownLocalAssets.documentFiles(recoveredFile).size)
            val pdf=File(folder,"markdown-images.pdf")
            com.localdoc.scanner.export.MarkdownPdf.write(app,recoveredFile.readText(),pdf,recoveredFile.parentFile)
            assertEquals(2,PdfTools.pageCount(pdf))
            val page=PdfTools.renderPage(pdf,1,595)!!
            try { assertTrue(Color.red(page.getPixel(300,180))>220);assertTrue(Color.green(page.getPixel(300,180))<40) } finally { page.recycle() }
        } finally { repo.observeDocs().first().filter { it.id !in before }.forEach { repo.deleteForever(it.id) };folder.deleteRecursively();backup.delete() }
        Unit
    }

    @Test fun signatureBackupRestoresOriginAndDeduplicatesSignatures(): Unit = runBlocking {
        val repo=DocRepository(app); val before=repo.observeDocs().first().map { it.id }.toSet()
        val input=File(app.cacheDir,"source-proof.txt").apply { writeText("来源记录测试") }
        val doc=repo.importFile(Uri.fromFile(input),"source-proof.txt","text/plain")
        val signature=SignatureStore.save(app,"备份测试-${System.nanoTime()}",listOf(listOf(PdfInkPoint(.1f,.2f),PdfInkPoint(.8f,.7f))))
        val originalSignatureHash=ToolCheckpoints.hash(signature.file)
        val backup=File(app.cacheDir,"library-v2.zip")
        var restoredSignature: SavedSignature?=null
        try {
            val result=repo.backupLibrary(Uri.fromFile(backup));assertTrue(result.error,result.success)
            ZipFile(backup).use { zip -> val root=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()); assertEquals(2,root.getInt("format")); assertTrue(root.getJSONArray("signatures").length()>0) }
            SignatureStore.remove(app,signature)
            assertTrue(repo.restoreLibrary(Uri.fromFile(backup)).success)
            restoredSignature=SignatureStore.all(app).single { it.name==signature.name }
            assertEquals(originalSignatureHash,ToolCheckpoints.hash(restoredSignature.file))
            val restored=repo.observeDocs().first().first { it.id!=doc && it.title.startsWith("source-proof") }; assertTrue(repo.sourceInfo(restored.id).contains(Uri.fromFile(input).toString())); assertTrue(File(restored.filePath).isFile)
            val count=SignatureStore.all(app).size
            assertTrue(repo.restoreLibrary(Uri.fromFile(backup)).success); assertEquals(count,SignatureStore.all(app).size)
        } finally {
            repo.observeDocs().first().filter { it.id !in before }.forEach { repo.deleteForever(it.id) }
            restoredSignature?.let { SignatureStore.remove(app,it) }; if(signature.file.exists())SignatureStore.remove(app,signature)
            input.delete(); backup.delete(); File(app.cacheDir,"signature-verify.png").delete()
        }
        Unit
    }
}
