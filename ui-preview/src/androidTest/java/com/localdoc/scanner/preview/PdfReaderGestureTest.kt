package com.localdoc.scanner.preview
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Rule
import org.junit.Test
class PdfReaderGestureTest {
    @get:Rule val activity=ActivityScenarioRule<PreviewActivity>(Intent(ApplicationProvider.getApplicationContext(),PreviewActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).putExtra("screen","pdf"))
    @get:Rule val compose=createEmptyComposeRule()
    private fun ready() { compose.waitUntil(15000) { compose.onAllNodesWithTag("pdf-pages").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun searchNavigatesToMatchAndInvalidJumpShowsErrorInsideDialog() {
        ready()
        compose.onNodeWithContentDescription("文内搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("PAGE 5")
        compose.waitUntil(10000) { compose.onAllNodesWithText("匹配 1 页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下个").performClick()
        compose.onNodeWithTag("pdf-page-4").assertIsDisplayed()
        compose.onNodeWithText("5 / 6 页").performClick()
        compose.onNode(hasSetTextAction() and hasText("5")).performTextReplacement("99")
        compose.onNodeWithText("跳转",useUnmergedTree=true).performClick()
        compose.onNodeWithText("请输入1至6的页码").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("99")).performTextReplacement("2")
        compose.onNodeWithText("跳转",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("pdf-page-1").assertIsDisplayed()
    }
    @Test fun draggingCanStopAcrossTwoPagesAndJump() {
        ready()
        compose.onNodeWithTag("pdf-pages").performTouchInput { swipeUp(startY=bottom*0.7f,endY=bottom*0.5f,durationMillis=1200) }
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-page-0").assertIsDisplayed()
        compose.onNodeWithTag("pdf-page-1").assertIsDisplayed()
        compose.onNodeWithText("1 / 6 页").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("5")
        compose.onNodeWithText("跳转",useUnmergedTree=true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-page-4").assertIsDisplayed()
    }
    @Test fun doubleTapAndPinchKeepScrollingAvailable() {
        ready()
        val before=compose.onNodeWithTag("pdf-page-0").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("pdf-pages").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        val after=compose.onNodeWithTag("pdf-page-0").fetchSemanticsNode().boundsInRoot.height
        org.junit.Assert.assertTrue(after>before)
        compose.onNodeWithTag("pdf-pages").performTouchInput {
            down(0,Offset(center.x-60,center.y)); down(1,Offset(center.x+60,center.y))
            moveTo(0,Offset(center.x-120,center.y)); moveTo(1,Offset(center.x+120,center.y))
            up(0); up(1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-pages").performTouchInput { swipeUp(durationMillis=500) }
        compose.onNodeWithTag("pdf-pages").assertIsDisplayed()
    }
    @Test fun optionalSnapSettlesOnOneWholePage() {
        ready()
        compose.onNodeWithContentDescription("阅读选项").performClick()
        compose.onNodeWithText("整页吸附").performClick()
        compose.onNodeWithTag("pdf-pages").performTouchInput { swipeUp(startY=bottom*0.8f,endY=bottom*0.25f,durationMillis=900) }
        compose.waitForIdle()
        val position=compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        // LazyList accessibility exposes item index plus fractional pixel offset.
        org.junit.Assert.assertEquals("Whole-page snap must have zero scroll offset",kotlin.math.round(position),position,0.00001f)
    }
}
