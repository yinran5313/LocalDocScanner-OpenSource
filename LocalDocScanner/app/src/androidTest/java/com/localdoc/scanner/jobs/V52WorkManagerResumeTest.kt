package com.localdoc.scanner.jobs

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run prepare, externally force-stop the isolated app, then run resume. */
class V52WorkManagerResumeTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun preparePausedTaskBeforeExternalRestart():Unit=runBlocking {
        val input=File(app.filesDir,"v52-proof/resume-source.pdf").apply {parentFile!!.mkdirs()}
        val pdf=PdfDocument()
        try {repeat(120) {index ->
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(400,600,index+1).create())
            page.canvas.drawText("RESUME TEST ${index+1}",20f,100f,Paint().apply {color=Color.BLACK;textSize=24f})
            pdf.finishPage(page)
        };input.outputStream().use(pdf::writeTo)} finally {pdf.close()}
        val request=ToolRequest(TOOL_ENTRIES.first {it.id=="pdf_text"},listOf(input),listOf("续跑TEST.pdf"))
        val id=ToolTasks.submit(app,request,emptyMap())
        val running=withTimeout(60000) { ToolTasks.watch(app).first {tasks ->tasks.any {it.id==id && it.completed>0} }.first {it.id==id} }
        assertTrue("Must pause before all units finish",running.completed<120)
        ToolTasks.pause(app,id)
        val paused=ToolTasks.status(app,id)!!
        assertEquals(ToolTaskState.PAUSED,paused.state)
        val firstUnit=File(ToolTasks.dir(app,id),"units").listFiles()!!.first()
        app.getSharedPreferences("v52-acceptance",0).edit().putString("resume-id",id)
            .putString("first-unit",firstUnit.absolutePath).putString("first-hash",ToolCheckpoints.hash(firstUnit))
            .putString("source-hash",ToolCheckpoints.hash(input)).commit()
    }
    @Test fun resumeAfterExternalProcessRestartCompletesWithoutRepausing():Unit=runBlocking {
        val prefs=app.getSharedPreferences("v52-acceptance",0)
        val id=requireNotNull(prefs.getString("resume-id",null))
        assertEquals(ToolTaskState.PAUSED,ToolTasks.status(app,id)?.state)
        val oldWork=ToolTasks.status(app,id)!!.workId
        ToolTasks.resume(app,id)
        assertNotEquals(oldWork,ToolTasks.status(app,id)!!.workId)
        val finished=withTimeout(120000) { ToolTasks.watch(app).first {tasks ->tasks.any {it.id==id && it.state !in setOf(ToolTaskState.QUEUED,ToolTaskState.RUNNING)} }.first {it.id==id} }
        assertEquals(finished.error,ToolTaskState.SUCCEEDED,finished.state)
        val text=ToolTasks.outcome(app,id)!!.files.single().readText()
        assertTrue(text.contains("RESUME TEST 1"));assertTrue(text.contains("RESUME TEST 120"))
        assertEquals(prefs.getString("first-hash",null),ToolCheckpoints.hash(File(prefs.getString("first-unit","")!!)))
        assertEquals(prefs.getString("source-hash",null),ToolCheckpoints.hash(File(app.filesDir,"v52-proof/resume-source.pdf")))
        File(app.filesDir,"v52-proof/resume-result.json").writeText(ToolTasks.gson.toJson(finished))
    }
}
