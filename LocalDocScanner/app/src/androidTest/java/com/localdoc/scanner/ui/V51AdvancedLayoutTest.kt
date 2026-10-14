package com.localdoc.scanner.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import com.localdoc.scanner.ui.tools.AdvancedToolsFlow
import com.localdoc.scanner.ui.tools.RegionEraseFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class V51AdvancedLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun newBusinessControlsWrapAtSmallWidthsLargeFontsAndLandscape() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm=AppViewModel(app)
        val input=File(app.cacheDir,"layout-v51.png"); val bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        for((id,field) in listOf("pdf_enhance" to "pdfSuperResolution","structured_markdown" to "mdTable","book_unwarp" to "splitSpread","region_erase" to "eraseLearned")) {
            val request=ToolRequest(TOOL_ENTRIES.first { it.id==id },listOf(input),listOf(input.name))
            app.getSharedPreferences("tool_drafts_v44",0).edit().putString(com.localdoc.scanner.data.ToolDrafts.key(request)+":"+field,"true").commit()
        }
        var tool by mutableStateOf("photo_restore"); var width by mutableIntStateOf(320); var height by mutableIntStateOf(640); var font by mutableFloatStateOf(1.5f)
        try {
            compose.setContent {
                val actual=LocalDensity.current.density; val config=LocalConfiguration.current
                val density=minOf(config.screenWidthDp*actual/width,(config.screenHeightDp-40)*actual/height)
                CompositionLocalProvider(LocalDensity provides Density(density,font)) { LocalDocScannerTheme { Box(Modifier.requiredSize(width.dp,height.dp)) {
                    key(tool,width,height,font) {
                        val request=ToolRequest(TOOL_ENTRIES.first { it.id==tool },listOf(input),listOf(input.name))
                        if(tool=="region_erase") RegionEraseFlow(request,vm,{},{},Modifier.fillMaxSize())
                        else AdvancedToolsFlow(request,vm,{},{},Modifier.fillMaxSize())
                    }
                } } }
            }
            for(id in listOf("photo_restore","pdf_enhance","structured_markdown","book_unwarp","region_erase")) {
                for((w,h,f) in listOf(Triple(280,640,1.5f),Triple(320,640,2f),Triple(600,800,1.3f),Triple(800,360,1.5f))) {
                    compose.runOnIdle { tool=id; width=w; height=h; font=f }
                    compose.waitForIdle()
                    val action=compose.onNodeWithText(if(id=="region_erase") "修补选区并预览" else "开始处理",useUnmergedTree=true)
                    action.assertIsDisplayed()
                    val controls=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).fetchSemanticsNodes()
                    for(node in controls) {
                        val layouts=mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                        val clipped=layouts.any { it.layoutInput.overflow!=TextOverflow.Ellipsis && (it.didOverflowHeight || (0 until it.lineCount).any { line -> it.getLineRight(line)>it.size.width+1f }) }
                        if(clipped) compose.onRoot().captureToImage().asAndroidBitmap().let { image -> File(app.filesDir,"testmodels/layout-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
                        assertFalse("Text clips at $id $w/$f: ${layouts.map { "${it.layoutInput.text}: size=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} overflow=${it.didOverflowWidth}/${it.didOverflowHeight}" }}",clipped)
                    }
                    if(id=="pdf_enhance") compose.onNodeWithText("页码范围",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                }
            }
        } finally { input.delete() }
    }
}
