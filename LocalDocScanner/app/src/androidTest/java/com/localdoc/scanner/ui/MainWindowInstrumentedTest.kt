package com.localdoc.scanner.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localdoc.scanner.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MainWindowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun productionHomeRespectsRealCutoutAndNavigationInsets() {
        compose.onNodeWithText("拾页").assertIsDisplayed()
        val decor = compose.activity.window.decorView
        val safe = ViewCompat.getRootWindowInsets(decor)!!.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val title = compose.onNodeWithText("拾页").fetchSemanticsNode().boundsInRoot
        assertTrue("Title overlaps status bar/cutout: $title / $safe", title.top >= safe.top - 1)
        val action = compose.onNodeWithText("导入 / 打开").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Action overlaps navigation bar", action.bottom <= decor.height - safe.bottom + 1)
        for (label in listOf("拍照扫描", "导入 / 打开", "文件留在本机")) {
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            for (layout in layouts) {
                assertTrue("Text is clipped by its parent: $label", text.fetchSemanticsNode().boundsInRoot.height >= layout.size.height - 1)
                assertFalse("Button text is vertically clipped: $label", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Button text is ellipsized: $label", layout.isLineEllipsized(line))
                    assertTrue("Button text exceeds width: $label", layout.getLineRight(line) <= layout.size.width + 1)
                }
            }
        }
    }
}
