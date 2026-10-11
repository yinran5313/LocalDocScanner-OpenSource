package com.localdoc.scanner.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.ui.components.*
import com.localdoc.scanner.ui.home.HomeScreen
import com.localdoc.scanner.ui.settings.DefaultSettings
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Test real production layouts at different logical sizes without changing device settings. */
class AdaptiveLayoutAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val widths = listOf(320, 360, 384, 412, 600)
    private val scales = listOf(1f, 1.3f, 1.5f, 2f)
    private var width by mutableIntStateOf(384)
    private var scale by mutableFloatStateOf(1f)
    private var screen by mutableStateOf("home")
    private var landscape by mutableStateOf(false)
    private var dark by mutableStateOf(false)
    private fun content() {
        compose.setContent {
            // All logical widths fit the host display, including a simulated tablet/landscape.
            val physical = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(physical / width, scale)) {
                LocalDocScannerTheme(darkTheme = dark) {
                    Box(Modifier.requiredSize(width.dp, (if (landscape) 360 else 640).dp).testTag("audit-viewport")) {
                        key(width, scale, screen, landscape, dark) {
                            when (screen) {
                                "settings" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DefaultSettings() }
                                "dock" -> AdaptiveActions(listOf(DockAction("暂停并保留进度", {}), DockAction("返回，后台继续", {}, style = ActionStyle.TEXT)))
                                else -> HomeScreen(emptyList(), 0, "", {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                    onToolClick = {}, onDocClick = {}, onRenameDoc = { _, _ -> }, onOrganizeDoc = { _, _, _ -> }, onTrashDoc = {})
                            }
                        }
                    }
                }
            }
        }
    }
    private fun verifyText(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        for (layout in layouts) assertTrue("Parent clips text: $label", node.fetchSemanticsNode().boundsInRoot.height >= layout.size.height - 1)
        assertTrue("No layout result for $label", layouts.isNotEmpty())
        assertFalse("Clipped label '$label' at ${width}dp / font $scale / $screen: " + layouts.joinToString { "size=${it.size}, right=${it.getLineRight(it.lineCount-1)}, bottom=${it.getLineBottom(it.lineCount-1)}" }, layouts.any(::clipsText))
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("audit-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Label outside viewport: $label", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
    }
    @Test fun homeActionsFitTwentyWidthAndFontCombinations() {
        content()
        for (w in widths) for (s in scales) {
            compose.runOnIdle { width = w; scale = s }
            verifyText("拍照扫描"); verifyText("导入 / 打开"); verifyText("文件留在本机")
            compose.onNodeWithText("导入 / 打开").performClick()
            compose.onNodeWithText("打开Office、PDF或文本").assertExists()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
    }
    @Test fun toolGridFitsSmallScreenLargeFontAndAllGroups() {
        content()
        for ((w, s) in listOf(320 to 1.5f, 384 to 2f, 600 to 1f)) {
            compose.runOnIdle { width = w; scale = s }
            for (group in listOf("图片处理", "PDF 处理", "识别与提取", "票据与表格")) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(group))
                compose.onNodeWithText(group).performScrollTo().performClick()
                val nodes = compose.onAllNodes(hasText("" , substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                for (n in nodes) {
                    val labels = n.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    val text = labels.joinToString("") { it.text }
                    if (text in listOf("图片编辑", "长图拼接", "图片转PDF", "PDF编辑", "证书数字签名", "文字识别", "表格转Excel")) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        if (n.config.contains(SemanticsActions.GetTextLayoutResult)) n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                        assertFalse("Grid clips '$text' at $w/$s: " + layouts.joinToString { "size=${it.size}, right=${it.getLineRight(it.lineCount-1)}, bottom=${it.getLineBottom(it.lineCount-1)}, left=${it.getLineLeft(0)}" }, layouts.any(::clipsText))
                    }
                }
                compose.onNodeWithText(group).performScrollTo().performClick()
            }
        }
    }
    @Test fun settingsOptionsWrapAndRemainReachable() {
        screen = "settings"; content()
        for (w in widths) for (s in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { width = w; scale = s }
            for (label in listOf("A4", "LETTER", "适合图片", "高清", "标准", "跟随系统", "浅色", "深色", "选择保存文件夹")) {
                compose.onNodeWithText(label).performScrollTo(); verifyText(label)
            }
        }
    }
    @Test fun longTaskActionsFitAndLandscapeRemainsUsable() {
        screen = "dock"; content()
        for (w in widths) for (s in scales) {
            compose.runOnIdle { width = w; scale = s }
            verifyText("暂停并保留进度"); verifyText("返回，后台继续")
        }
        compose.runOnIdle { width = 800; scale = 1.5f; landscape = true; screen = "home"; dark = true }
        verifyText("拍照扫描"); verifyText("导入 / 打开")
    }

    // Compose floors its integer size while paragraph edges remain fractional. A <1px
    // rounding difference is not a missing glyph; still reject ellipses and real clipping.
    private fun clipsText(layout: TextLayoutResult): Boolean =
        (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) ||
            layout.getLineRight(line) > layout.size.width + 1f || layout.getLineLeft(line) < -1f ||
            layout.getLineBottom(line) > layout.size.height + 1f }
}
