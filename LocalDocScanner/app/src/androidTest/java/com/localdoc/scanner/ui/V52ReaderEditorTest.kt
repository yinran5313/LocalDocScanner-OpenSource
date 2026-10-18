package com.localdoc.scanner.ui

import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.pdf.PdfReadSession
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.localdoc.scanner.ui.tools.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class V52ReaderEditorTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun sample(name:String,count:Int):File {
        val target=File(app.cacheDir,name)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pdf ->
            repeat(count) { index ->
                val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(600f,800f));pdf.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdf,page).use { stream ->
                    stream.beginText();stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,36f)
                    stream.newLineAtOffset(40f,680f);stream.showText("TARGET${index+1}");stream.endText()
                }
            }
            pdf.save(target)
        }
        return target
    }

    @Test fun closingSearchAndChangingTextModeKeepsPageTwentyInSnapMode() {
        val file=sample("v52-reading.pdf",46)
        try {
            compose.setContent { LocalDocScannerTheme { PdfReader(file,Modifier.fillMaxSize(),initialPage=19) } }
            compose.waitUntil(20000) { compose.onAllNodesWithText("20 / 46 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("阅读选项").performClick()
            compose.onNodeWithText("整页吸附").performClick()
            compose.onNodeWithContentDescription("文内搜索").performClick()
            compose.onNodeWithText("搜索文内文字").performTextInput("TARGET20")
            compose.waitUntil(20000) { compose.onAllNodesWithText("匹配 1 页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("关闭搜索").performClick()
            compose.onNodeWithText("20 / 46 页").assertIsDisplayed()
            compose.onNodeWithContentDescription("阅读选项").performClick()
            compose.onNodeWithText("文字 · 长按复制").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("TARGET20",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("TARGET20",substring=true).assertIsDisplayed()
        } finally { file.delete() }
    }

    @Test fun freeDraggingStopsBetweenPagesAndPinchThenDoubleTapRestoresFit() {
        val file=sample("v52-gestures.pdf",5)
        val session=PdfReadSession.open(file)
        var state:androidx.compose.foundation.lazy.LazyListState?=null
        try {
            compose.setContent { LocalDocScannerTheme {
                val list=rememberLazyListState(); SideEffect { state=list }
                PdfContinuousPages(session,list,false,{},Modifier.fillMaxSize())
            } }
            compose.onNodeWithTag("pdf-pages").performTouchInput { swipe(Offset(center.x,height*.8f),Offset(center.x,height*.5f),2000) }
            compose.runOnIdle { assertTrue("Continuous drag must retain an intermediate offset",state!!.firstVisibleItemScrollOffset>10) }
            // Every fixture page has the same dimensions. Use its full measured
            // size: a correctly anchored zoom may virtualize page 0 away, and
            // boundsInRoot is clipped by the viewport rather than the paper size.
            val before=compose.runOnIdle { state!!.layoutInfo.visibleItemsInfo.first().size }
            compose.onNodeWithTag("pdf-pages").performTouchInput {
                pinch(Offset(center.x-40,center.y),Offset(center.x+40,center.y),Offset(center.x-110,center.y),Offset(center.x+110,center.y),600)
            }
            compose.waitForIdle()
            val enlarged=compose.runOnIdle { state!!.layoutInfo.visibleItemsInfo.first().size }
            assertTrue("Pinch must enlarge page",enlarged>before*1.4f)
            compose.onNodeWithTag("pdf-pages").performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            compose.onNodeWithTag("pdf-pages").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"缩放100%"))
        } finally { session.close();file.delete() }
    }

    @Test fun selectedPdfObjectCanMoveDeleteUndoAndEditFromList() {
        val file=sample("v52-object-${java.util.UUID.randomUUID()}.pdf",1)
        val request=ToolRequest(TOOL_ENTRIES.first {it.id=="pdf_office"},listOf(file),listOf("对象TEST.pdf"))
        val vm=AppViewModel(app)
        try {
            compose.setContent {LocalDocScannerTheme {PdfOfficeFlow(request,vm,{},{},Modifier.fillMaxSize())}}
            compose.onNodeWithText("文字标记").performClick()
            compose.onNodeWithContentDescription("移除当前对象").assertIsDisplayed()
            val before=com.localdoc.scanner.data.ToolDrafts.snapshot(app,request)
            compose.onNodeWithTag("pdf-edit-canvas").performTouchInput {swipe(center,center+Offset(80f,50f),500)}
            val moved=com.localdoc.scanner.data.ToolDrafts.snapshot(app,request)
            assertTrue(listOf("x","y","width","height").any {before[it]!=moved[it]})
            compose.onNodeWithContentDescription("撤销位置或移除").performClick()
            val restored=com.localdoc.scanner.data.ToolDrafts.snapshot(app,request)
            assertTrue(listOf("x","y","width","height").all {before[it]==restored[it]})
            compose.onNodeWithContentDescription("移除当前对象").performClick()
            compose.onNodeWithText("生成副本").assertIsNotEnabled()
            compose.onNodeWithContentDescription("撤销位置或移除").performClick()
            compose.onNodeWithText("生成副本").assertIsEnabled()
            compose.onNodeWithText("加入").performClick()
            compose.onNodeWithText("参数 / 清单",substring=true).performClick()
            compose.onNodeWithText("选中编辑").performScrollTo().performClick()
            compose.onNodeWithContentDescription("移除当前对象").assertIsDisplayed()
        } finally {file.delete()}
    }

    @Test fun pdfEditorKeepsCanvasAndControlsAccessibleAcrossWindowSizes() {
        val file=sample("v52-editor.pdf",2)
        val vm=AppViewModel(app)
        var width by mutableIntStateOf(320);var height by mutableIntStateOf(640);var font by mutableFloatStateOf(1.5f)
        try {
            compose.setContent {
                val physical=LocalDensity.current.density; val config=LocalConfiguration.current
                val density=minOf(config.screenWidthDp*physical/width,(config.screenHeightDp-40)*physical/height)
                CompositionLocalProvider(LocalDensity provides Density(density,font)) { LocalDocScannerTheme {
                    Box(Modifier.requiredSize(width.dp,height.dp)) { key(width,height,font) {
                        PdfOfficeFlow(ToolRequest(TOOL_ENTRIES.first {it.id=="pdf_office"},listOf(file),listOf("编辑TEST.pdf")),vm,{},{},Modifier.fillMaxSize())
                    } }
                } }
            }
            for((w,h,f) in listOf(Triple(280,640,1.5f),Triple(320,640,2f),Triple(800,360,1.5f),Triple(600,800,1.3f))) {
                compose.runOnIdle {width=w;height=h;font=f}
                compose.waitForIdle()
                val canvas=compose.onNodeWithTag("pdf-edit-canvas")
                try {canvas.assertIsDisplayed()} catch(e:AssertionError) {
                    val proof=File(app.filesDir,"v52-proof").apply {mkdirs()}
                    File(proof,"editor-$w-$h-$f.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
                    compose.onRoot().captureToImage().asAndroidBitmap().let {image -> File(proof,"editor-$w-$h-$f.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
                    throw AssertionError("Canvas hidden at $w/$h/$f",e)
                }
                assertTrue("Canvas too short at $w/$h/$f",canvas.fetchSemanticsNode().boundsInRoot.height>70)
                compose.onNodeWithText("生成副本").assertIsDisplayed()
                if(w>h) compose.onNodeWithContentDescription("参数 / 清单").performClick() else compose.onNodeWithText("参数 / 清单").performClick()
                compose.onNodeWithText("返回画布").assertIsDisplayed().performClick()
            }
        } finally {file.delete()}
    }
}
