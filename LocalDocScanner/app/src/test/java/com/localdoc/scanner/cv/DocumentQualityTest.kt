package com.localdoc.scanner.cv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQualityTest {
    @Test fun whitePaperWithDarkPrintIsNotGlareOrOverexposed() {
        val pixels = IntArray(10000) { i -> if (i % 100 in 20..70 && i / 100 % 10 == 0) 40 else 255 }
        val result = DocumentQuality.analyzeLuma(pixels, 100, 100)
        assertFalse(result.warnings.any { it.contains("反光") || it.contains("过亮") })
    }

    @Test fun compactSaturatedSpotOnDarkPaperWarnsAboutGlare() {
        val pixels = IntArray(10000) { i -> if (i % 100 in 30..60 && i / 100 in 30..60) 255 else 140 }
        assertTrue(DocumentQuality.analyzeLuma(pixels, 100, 100).warnings.any { it.contains("反光") })
    }
    @Test
    fun darkUniformPageReportsDarkAndBlur() {
        val result = DocumentQuality.analyzeLuma(IntArray(100) { 20 }, 10, 10)
        assertTrue(result.warnings.any { it.contains("偏暗") })
        assertTrue(result.warnings.any { it.contains("模糊") })
    }

    @Test
    fun sharpCheckerboardIsNotReportedAsBlur() {
        val pixels = IntArray(400) { index ->
            val x = index % 20
            val y = index / 20
            if ((x + y) % 2 == 0) 20 else 235
        }
        val result = DocumentQuality.analyzeLuma(pixels, 20, 20)
        assertFalse(result.warnings.any { it.contains("模糊") })
    }

    @Test
    fun brightPageReportsOverexposure() {
        val result = DocumentQuality.analyzeLuma(IntArray(144) { 252 }, 12, 12)
        assertTrue(result.warnings.any { it.contains("过亮") })
    }
}
