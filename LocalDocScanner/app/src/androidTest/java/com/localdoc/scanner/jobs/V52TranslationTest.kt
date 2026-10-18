package com.localdoc.scanner.jobs

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.BitmapOwnership
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class V52TranslationTest {
    @Test fun completedLegacyOcrSurvivesTranslationReviewWithoutRunningOcrAgain():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val pack=ModelCatalog.get(app,"translate-en-zh")
        if(!ModelCatalog.installed(app,pack)) ModelDownloads.import(app,Uri.fromFile(File(app.filesDir,"v52-testmodels/translate-en-zh.zip")))
        val input=File(app.cacheDir,"translation-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        input.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val id=UUID.randomUUID().toString()
        try {
            val original="Invoice amount is 40.00 yuan."
            val journal=ToolCheckpoints(ToolTasks.dir(app,id),ToolTasks.gson)
            journal.save("translate-ocr",ToolTasks.gson.toJson(original),emptyList())
            val spec=ToolTaskSpec(id,ToolRequest(TOOL_ENTRIES.first {it.id=="translate"},listOf(input),listOf("翻译TEST.png")),mapOf("chineseToEnglish" to "false"),mapOf(input.absolutePath to ToolCheckpoints.hash(input)))
            val processor=ToolTaskProcessor(app,spec,null) {_,_,_ ->}.apply {recognizerForTest=object:OcrEngine {
                override val available=true;override val label="must not run"
                override suspend fun recognize(bitmap:Bitmap,precise:Boolean):OcrOutcome=error("已完成的OCR不应重跑")
            }}
            val result=processor.run()
            assertEquals(original,result.reviewPages.single().raw)
            assertTrue(result.reviewPages.single().fields["译文"].orEmpty().isNotBlank())
            assertNotNull(journal.load("translate-ocr-v52"))
            val copy=File(app.filesDir,"v52-proof/translation.txt").apply {parentFile!!.mkdirs()};result.files.single().copyTo(copy,overwrite=true)
            val overrideSpec=spec.copy(id=UUID.randomUUID().toString(),action="translate_review",parameters=mapOf("chineseToEnglish" to "false","translationSource" to ToolTasks.gson.toJson("Invoice amount is 41.00 yuan.")))
            try {
                var total=0
                val reviewed=ToolTaskProcessor(app,overrideSpec,null) {_,n,_ ->total=n}.run()
                assertEquals(1,total)
                assertTrue(reviewed.files.single().readText().contains("41.00"))
                assertNull(ToolCheckpoints(ToolTasks.dir(app,overrideSpec.id),ToolTasks.gson).load("translate-ocr-v52"))
            } finally {ToolTasks.dir(app,overrideSpec.id).deleteRecursively()}
        } finally {input.delete();ToolTasks.dir(app,id).deleteRecursively()}
    }
    @Test fun displayedBitmapCanStillBeDrawnAfterItsWorkerAndScreenReleaseIt() {
        val ownership=BitmapOwnership();val source=ownership.adopt(Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888))
        ownership.display(source);assertTrue(ownership.acquire(source));ownership.dispose();ownership.release(source)
        assertFalse(source.isRecycled)
        val frame=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
        try {Canvas(frame).drawBitmap(source,0f,0f,Paint())} finally {frame.recycle();source.recycle()}
        val pending=BitmapOwnership();val undisplayed=pending.adopt(Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888));pending.dispose();assertTrue(undisplayed.isRecycled)
    }
}
