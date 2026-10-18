package com.localdoc.scanner.jobs

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.reflect.TypeToken
import com.localdoc.scanner.models.*
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.structure.*
import com.localdoc.scanner.ui.ToolRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class V52TableRegressionTest {
    @Test fun sameS14MediumReceiptProducesFiveColumnsThroughDirectXlsxPath() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        for(id in listOf("layout","table")) {
            val pack=ModelCatalog.get(app,id)
            if(!ModelCatalog.installed(app,pack)) ModelDownloads.import(app,Uri.fromFile(File(app.filesDir,"v52-testmodels/$id.zip")))
        }
        val json=instrumentation.context.assets.open("v52/S14_medium_boxes.json").bufferedReader().use {it.readText()}
        val boxes:List<OcrTextBox> = ToolTasks.gson.fromJson(com.google.gson.JsonParser.parseString(json).asJsonObject["boxes"],object:TypeToken<List<OcrTextBox>>(){}.type)
        val input=File(app.cacheDir,"S14_TEST_02_TABLE_1017.jpg")
        instrumentation.context.assets.open("v52/S14_table.jpg").use {source -> input.outputStream().use(source::copyTo)}
        val id=UUID.randomUUID().toString()
        try {
            val spec=ToolTaskSpec(id,ToolRequest(TOOL_ENTRIES.first {it.id=="table_xlsx"},listOf(input),listOf(input.name)),mapOf("useTableModel" to "true"),mapOf(input.absolutePath to ToolCheckpoints.hash(input)))
            var maxDone=0;var maxTotal=0
            val processor=ToolTaskProcessor(app,spec,null) {done,total,_ ->maxDone=maxOf(maxDone,done);maxTotal=total}.apply {
                // Actual medium output saved by the prior device run, reused without rerunning OCR.
                recognizerForTest=object:OcrEngine {
                    override val available=true;override val label="已完成的medium识别回执"
                    override suspend fun recognize(bitmap:Bitmap,precise:Boolean)=OcrOutcome(boxes.joinToString("\n") {it.text},boxes.size,0,0,boxes)
                }
            }
            val page=processor.run().reviewPages.single()
            assertEquals(page.layoutNote,5,page.rows.maxOf {it.size})
            assertEquals(8,page.rows.size)
            assertEquals(listOf("日期","摘要","收入","支出","余额"),page.rows.first().map {it.trim()})
            assertFalse(page.rows.flatten().any {it.contains("TEST") || it.contains("DEMO")})
            assertEquals(2,maxTotal);assertTrue(maxDone<=maxTotal)
            val target=File(app.filesDir,"v52-proof/S14-v52.xlsx").apply {parentFile!!.mkdirs()}
            SpreadsheetExport.writeTables(target,listOf(TableSheet("S14",page.rows,page.merges.orEmpty(),ColumnInference.suggest(page.rows))))
            ZipFile(target).use {zip -> assertTrue(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText().contains("<dimension ref=\"A1:E8\"/>"))}
            File(target.parentFile,"S14-v52-review.json").writeText(ToolTasks.gson.toJson(page))
        } finally {input.delete();ToolTasks.dir(app,id).deleteRecursively()}
    }
}
