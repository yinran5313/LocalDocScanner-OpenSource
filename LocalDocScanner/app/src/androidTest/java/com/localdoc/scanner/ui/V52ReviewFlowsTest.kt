package com.localdoc.scanner.ui

import android.graphics.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.localdoc.scanner.ui.tools.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.min

class V52ReviewFlowsTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun image():File {
        val file=File(app.cacheDir,"v52-review-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(bitmap).drawText("TEST 40.00",20f,100f,Paint().apply {color=Color.BLACK;textSize=30f})
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return file
    }
    @Test fun countReviewAddsDeletesUndoesAndExportsTheConfirmedQuantity() {
        val input=image();val detail=File(app.cacheDir,"v52-count-${UUID.randomUUID()}.json")
        val boxes=(0 until 24).map {val x=.1f+it%6*.1f;val y=.1f+it/6*.1f;CountedObject(.9f,x,y,x+.02f,y+.02f)}
        detail.writeText(ToolTasks.gson.toJson(boxes))
        val request=ToolRequest(TOOL_ENTRIES.first {it.id=="count_objects"},listOf(input),listOf("计数TEST.png"))
        try {
            compose.setContent {LocalDocScannerTheme {CountReviewDialog(request,detail,"测试目标",{})}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("数量：24").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("count-review-canvas").fetchSemanticsNodes().isNotEmpty()}
            fun tapMissing() {compose.onNodeWithTag("count-review-canvas").performTouchInput {val side=min(width,height);click(Offset((width-side)/2+side*.9f,(height-side)/2+side*.9f))}}
            tapMissing();compose.onNodeWithText("数量：25").assertIsDisplayed()
            compose.onNodeWithText("删除").performClick();tapMissing();compose.onNodeWithText("数量：24").assertIsDisplayed()
            compose.onNodeWithText("撤销").performClick();compose.onNodeWithText("数量：25").assertIsDisplayed()
            compose.onNodeWithText("重做").performClick();compose.onNodeWithText("数量：24").assertIsDisplayed()
            compose.onNodeWithText("确认并生成结果").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("人工复核计数.zip").fetchSemanticsNodes().isNotEmpty()}
            val output=File(app.filesDir,"count-reviews").listFiles()!!.maxBy {it.lastModified()}
            val json=com.google.gson.JsonParser.parseString(File(output,"人工复核计数.json").readText()).asJsonObject
            assertEquals(24,json["count"].asInt);assertTrue(json["confirmed"].asBoolean)
            assertTrue(File(output,"人工复核计数.zip").length()>0)
        } finally {input.delete();detail.delete()}
    }
    @Test fun ocrEditsKeepOriginalVisibleAndProduceANewTextCopy() {
        val input=image();val job=UUID.randomUUID().toString();val key="ocr:0:0"
        ToolCheckpoints(ToolTasks.dir(app,job),ToolTasks.gson).save(key,ToolTasks.gson.toJson(mapOf("source" to input.absolutePath,"page" to 0,"text" to "TEST 40.00\nSECOND LINE",
            "boxes" to listOf(OcrTextBox("TEST 40.00",.05f,.17f,.7f,.27f)))),emptyList())
        val request=ToolRequest(TOOL_ENTRIES.first {it.id=="ocr"},listOf(input),listOf("OCR测试.png"))
        try {
            compose.setContent {LocalDocScannerTheme {OcrReviewDialog(request,job,listOf(key),{})}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("第1行").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("第1行").performTextReplacement("TEST 41.00")
            compose.onNodeWithTag("source-review").assertIsDisplayed()
            compose.onNodeWithText("确认本页").performClick()
            compose.onNodeWithText("生成文字副本").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("校订文字.txt").fetchSemanticsNodes().isNotEmpty()}
            val output=File(app.filesDir,"ocr-reviews").listFiles()!!.maxBy {it.lastModified()}
            val text=File(output,"校订文字.txt").readText()
            assertTrue(text.contains("TEST 41.00"));assertTrue(text.contains("已确认"))
            assertTrue(OcrReviewStore.load(app,job,key).raw.contains("TEST 40.00"))
        } finally {input.delete();ToolTasks.dir(app,job).deleteRecursively()}
    }
}
