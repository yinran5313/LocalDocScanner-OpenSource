package com.localdoc.scanner.ui

import android.graphics.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.edit.EditScreen
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.localdoc.scanner.util.ImageIo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class EditorLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewAndEnhancementControlsSurvivePortraitLandscapeAndLargeFonts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "layout-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
            repeat(10) { drawText("SHIYE 501 ${it + 1}", 30f, 70f + it * 40, paint) }
        }
        assertTrue(ImageIo.saveJpeg(bitmap, input)); bitmap.recycle()
        var width by mutableIntStateOf(384)
        var height by mutableIntStateOf(640)
        var scale by mutableFloatStateOf(1f)
        try {
            compose.setContent {
                val physical = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(physical / width, scale)) {
                    LocalDocScannerTheme {
                        Box(Modifier.requiredSize(width.dp, height.dp)) {
                            key(width, height, scale) { EditScreen(input.absolutePath, 0, onConfirm = {}, onRetake = {}, onBack = {}) }
                        }
                    }
                }
            }
            for ((w, h, s) in listOf(Triple(320,640,1.5f), Triple(384,640,2f), Triple(800,360,1.5f), Triple(600,800,1f))) {
                compose.runOnIdle { width=w; height=h; scale=s }
                compose.waitUntil(15000) { compose.onAllNodesWithText("下一步").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("scan-preview").assertIsDisplayed()
                assertTrue("Crop preview must keep a useful height", compose.onNodeWithTag("scan-preview").fetchSemanticsNode().boundsInRoot.height > 80)
                compose.onNodeWithText("下一步").performScrollToIfNeeded().performClick()
                compose.onNodeWithTag("scan-preview").assertIsDisplayed()
                assertTrue("Enhancement preview must stay visible", compose.onNodeWithTag("scan-preview").fetchSemanticsNode().boundsInRoot.height > 80)
                val save = compose.onNodeWithText("保存此页", useUnmergedTree=true)
                if(w>h) save.performScrollTo()
                save.assertIsDisplayed()
                val layout=mutableListOf<TextLayoutResult>()
                save.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertFalse("Save label clips at $w/$s", layout.any { it.getLineRight(it.lineCount-1) > it.size.width + 1f || it.didOverflowHeight })
            }
        } finally { input.delete() }
    }
    // Portrait controls are pinned; landscape puts the footer in a scrollable side pane.
    private fun SemanticsNodeInteraction.performScrollToIfNeeded(): SemanticsNodeInteraction {
        runCatching { performScrollTo() }; return this
    }
}
