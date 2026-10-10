package com.localdoc.scanner

import org.junit.Assert.*
import org.junit.Test
import org.libreoffice.androidlib.OfficeUiLanguage
import java.util.Locale

class OfficeUiLanguageTest {
    @Test fun chineseScriptAndRegionVariantsMatchOfflineBundle() {
        mapOf("zh" to "zh-CN", "zh-CN" to "zh-CN", "zh-Hans-CN" to "zh-CN",
            "zh-Hans-HK" to "zh-CN", "zh-Hant" to "zh-TW", "zh-HK" to "zh-TW",
            "zh-MO" to "zh-TW", "zh-TW" to "zh-TW", "en-US" to "en-US").forEach { (tag, expected) ->
            assertEquals(tag, expected, OfficeUiLanguage.normalize(Locale.forLanguageTag(tag)))
        }
    }
    @Test fun localizationRunsAfterBundleInSourceOrder() {
        val base = "<script src=\"bundle.js\" defer></script>"
        val attached = OfficeUiLanguage.attachTranslations(base)
        assertTrue(attached.indexOf("bundle.js") < attached.indexOf("office-ui.js"))
        assertTrue(attached.indexOf("office-ui.js") < attached.indexOf("zh-data.js"))
        assertTrue(attached.indexOf("bundle.js") < attached.indexOf("zh-data.js"))
        assertTrue(attached.indexOf("zh-data.js") < attached.indexOf("office-zh.js"))
    }
    @Test(expected = IllegalStateException::class) fun changedUpstreamEntryIsDetected() {
        OfficeUiLanguage.attachTranslations("<html></html>")
    }
}
