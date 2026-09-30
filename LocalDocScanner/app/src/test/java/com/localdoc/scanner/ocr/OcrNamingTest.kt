package com.localdoc.scanner.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrNamingTest {
    @Test
    fun skipsPageMarkerAndSanitizesFileName() {
        val value = OcrNaming.suggest("【第 1 页】\n增值税/纳税申报表：2026年")
        assertEquals("增值税 纳税申报表：2026年", value)
    }
}
