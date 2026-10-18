package com.localdoc.scanner.ui

import android.app.Application
import android.graphics.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.barcode.*
import com.localdoc.scanner.data.ToolDrafts
import com.localdoc.scanner.jobs.ReviewPage
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.localdoc.scanner.ui.tools.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class V52WorkspaceLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun input():File {
        val file=File(app.cacheDir,"workspace-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(bitmap).drawText("TOTAL 40.00",25f,130f,Paint().apply {color=Color.BLACK;textSize=38f})
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return file
    }
    @Test fun imageWorkspaceKeepsPreviewAndActionsAtNarrowLargeFontAndLandscapeSizes() {
        val input=input();val vm=AppViewModel(app)
        var width by mutableIntStateOf(280);var height by mutableIntStateOf(640);var font by mutableFloatStateOf(1.5f)
        try {
            compose.setContent {
                val physical=LocalDensity.current.density;val config=LocalConfiguration.current
                val density=minOf(config.screenWidthDp*physical/width,(config.screenHeightDp-40)*physical/height)
                CompositionLocalProvider(LocalDensity provides Density(density,font)) {LocalDocScannerTheme {
                    Box(Modifier.requiredSize(width.dp,height.dp)) {key(width,height,font) {
                        ImageEditFlow(ToolRequest(TOOL_ENTRIES.first {it.id=="image_edit"},listOf(input),listOf("图片TEST.png")),vm,{},{},Modifier.fillMaxSize())
                    }}
                }}
            }
            for((w,h,f) in listOf(Triple(280,640,1.5f),Triple(320,640,2f),Triple(800,360,1.5f),Triple(600,800,1.3f))) {
                compose.runOnIdle {width=w;height=h;font=f}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("image-edit-canvas").fetchSemanticsNodes().isNotEmpty()}
                val canvas=compose.onNodeWithTag("image-edit-canvas");canvas.assertIsDisplayed()
                assertTrue("Image canvas too small at $w/$h/$f",canvas.fetchSemanticsNode().boundsInRoot.height>70)
                compose.onNodeWithText("生成1页").assertIsDisplayed()
            }
        } finally {input.delete()}
    }
    @Test fun structuredFieldsStayLinkedWhileDividerAndKeyboardAreUsed() {
        val input=input();val vm=AppViewModel(app)
        val request=ToolRequest(TOOL_ENTRIES.first {it.id=="batch_extract"},listOf(input),listOf("收据TEST.png"))
        val page=ReviewPage(input.absolutePath,0,"TOTAL 40.00",mapOf("商户" to "TEST店","金额" to "40.00"),listOf(listOf("名称","金额"),listOf("纸","40.00")),sourceBoxes=listOf(OcrTextBox("TOTAL 40.00",.05f,.15f,.8f,.25f)))
        app.getSharedPreferences("tool_drafts_v44",0).edit().putString(ToolDrafts.key(request)+":reviewPages",ToolDrafts.gson.toJson(listOf(page))).putString(ToolDrafts.key(request)+":batchKind","1").commit()
        try {
            compose.setContent {LocalDocScannerTheme {StructuredWorkbench(request,vm,{},Modifier.fillMaxSize())}}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("source-review").fetchSemanticsNodes().isNotEmpty()}
            val before=compose.onNodeWithTag("source-review").fetchSemanticsNode().boundsInRoot.height
            compose.onNodeWithTag("review-divider").performTouchInput {swipe(center,center+Offset(0f,100f),500)}
            assertTrue(compose.onNodeWithTag("source-review").fetchSemanticsNode().boundsInRoot.height>before+20f)
            compose.onNodeWithTag("review-field-金额").performScrollTo().performClick().performTextReplacement("41.00")
            compose.onNodeWithTag("source-review").assertIsDisplayed()
            compose.onNodeWithTag("source-review-focus").assertIsDisplayed()
            val saved=ToolDrafts.snapshot(app,request)["reviewPages"]!!
            assertTrue(saved.contains("41.00"));assertTrue(saved.contains("\"reviewed\":false"))
        } finally {input.delete()}
    }
    @Test fun barcodeNameSurvivesRescanAndDetailsRemainAvailableWithoutCamera() {
        val text="TEST_V52_${UUID.randomUUID()}"
        BarcodeHistory.add(app,text,"QR_CODE")
        var record by mutableStateOf(BarcodeHistory.all(app).first {it.text==text})
        compose.setContent {LocalDocScannerTheme {BarcodeResultDialog(record,{}, {name ->BarcodeHistory.rename(app,text,name);record=BarcodeHistory.all(app).first {it.text==text}})}}
        compose.onNodeWithText("名称，方便以后查找").performTextInput("报销TEST")
        compose.onNodeWithText("保存名称").performClick()
        assertEquals("报销TEST",BarcodeHistory.all(app).first {it.text==text}.name)
        BarcodeHistory.add(app,text,"QR_CODE")
        assertEquals("报销TEST",BarcodeHistory.all(app).first {it.text==text}.name)
        compose.onNodeWithText("类型：二维码",substring=true).assertIsDisplayed()
        compose.onNodeWithText("复制内容").assertIsDisplayed()
    }
}
