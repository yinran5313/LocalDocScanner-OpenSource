package com.localdoc.scanner

import android.webkit.WebView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localdoc.scanner.ui.tools.FormulaPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FormulaPreviewAndroidTest {
    @get:Rule val compose=createComposeRule()
    @Test fun katexRendersFromBundledAssetsWithNetworkBlocked() {
        val view=AtomicReference<WebView>(); val result=AtomicReference<String>()
        compose.setContent { FormulaPreview("\\frac{x^2}{a^2}-\\frac{y^2}{b^2}=1") { view.set(it) } }
        compose.waitUntil(20000) { view.get()!=null }
        compose.runOnUiThread { assertTrue(view.get().settings.blockNetworkLoads); view.get().evaluateJavascript("typeof katex!=='undefined' && !!document.querySelector('.katex')") { result.set(it) } }
        compose.waitUntil(5000) { result.get()!=null }; assertEquals("true",result.get())
    }
}
