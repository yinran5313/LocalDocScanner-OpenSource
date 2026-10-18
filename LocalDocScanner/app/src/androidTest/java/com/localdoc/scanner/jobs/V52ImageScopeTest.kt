package com.localdoc.scanner.jobs

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.ToolRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class V52ImageScopeTest {
    @Test fun reorderedBatchEditsOnlyChosenImageAndCopiesOtherBytesExactly():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val files=(0..1).map {File(app.cacheDir,"scope-${UUID.randomUUID()}.png")}
        files.forEach {file ->val bitmap=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)};file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        val id=UUID.randomUUID().toString()
        try {
            val parameters=mapOf("imageScope" to "2","imageTargets" to ToolTasks.gson.toJson(listOf(files[0].absolutePath)),"imageOrder" to ToolTasks.gson.toJson(files.reversed()),"imageBatchPdf" to "false","filter" to "\"GRAY\"","rotation" to "1")
            val spec=ToolTaskSpec(id,ToolRequest(TOOL_ENTRIES.first {it.id=="image_edit"},files,files.map {it.name}),parameters,files.associate {it.absolutePath to ToolCheckpoints.hash(it)})
            val result=ToolTaskProcessor(app,spec,null) {_,_,_ ->}.run()
            assertTrue(result.failures.toString(),result.failures.isEmpty());assertEquals(2,result.files.size)
            assertEquals(ToolCheckpoints.hash(files[1]),ToolCheckpoints.hash(result.files[0]))
            val edited=BitmapFactory.decodeFile(result.files[1].absolutePath)
            try {assertEquals(100,edited.width);assertEquals(80,edited.height);val color=edited.getPixel(30,30);assertTrue(kotlin.math.abs(Color.red(color)-Color.green(color))<=2);assertTrue(kotlin.math.abs(Color.red(color)-Color.blue(color))<=2)} finally {edited.recycle()}
        } finally {files.forEach {it.delete()};ToolTasks.dir(app,id).deleteRecursively()}
    }
}
