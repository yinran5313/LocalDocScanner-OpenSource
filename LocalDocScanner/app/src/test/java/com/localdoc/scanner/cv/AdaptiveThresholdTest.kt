package com.localdoc.scanner.cv

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AdaptiveThresholdTest {
    @Test
    fun unevenIlluminationKeepsPaperWhiteAndTextBlack() {
        val width = 240
        val height = 140
        val gray = IntArray(width * height)
        val textMask = BooleanArray(gray.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // 从左侧阴影到右侧亮区，并叠加一条竖向折痕。
                var background = 78.0 + 164.0 * x / (width - 1)
                if (x in 104..124) background -= 34.0 * (1.0 - kotlin.math.abs(x - 114) / 11.0)
                val isText = (y in 25..34 && x in 18..215) ||
                    (y in 63..71 && x in 35..205) ||
                    (y in 98..112 && x in 12..225 && (x / 4) % 2 == 0)
                val index = y * width + x
                textMask[index] = isText
                gray[index] = (background - if (isText) 58.0 else 0.0).roundToInt().coerceIn(0, 255)
            }
        }

        val binary = adaptiveDocumentThreshold(gray, width, height, radius = 18)
        var textBlack = 0
        var textCount = 0
        var paperWhite = 0
        var paperCount = 0
        for (i in binary.indices) {
            if (textMask[i]) {
                textCount++
                if (binary[i] == 0) textBlack++
            } else {
                paperCount++
                if (binary[i] == 255) paperWhite++
            }
        }

        assertTrue("dark text should survive local shadows", textBlack.toDouble() / textCount > 0.86)
        assertTrue("shadowed paper should not become a black block", paperWhite.toDouble() / paperCount > 0.94)
    }

    @Test
    fun rejectsInvalidDimensions() {
        val result = runCatching { adaptiveDocumentThreshold(IntArray(3), 2, 2) }
        assertTrue(result.isFailure)
    }

    @Test
    fun illuminationNormalizationFlattensShadowButKeepsTextContrast() {
        val width = 180
        val height = 80
        val gray = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val background = 75 + 165 * x / (width - 1)
            val text = y in 30..38
            gray[y * width + x] = (background - if (text) 45 else 0).coerceIn(0, 255)
        }
        val normalized = normalizeDocumentIllumination(gray, width, height, radius = 20)
        val leftBackground = normalized[10 * width + 20]
        val rightBackground = normalized[10 * width + 160]
        assertTrue("background should be locally flattened", kotlin.math.abs(leftBackground - rightBackground) < 18)
        val text = normalized[34 * width + 90]
        val background = normalized[15 * width + 90]
        assertTrue("text should remain darker than paper", background - text > 28)
    }

    @Test
    fun removesLargeBlackBackgroundConnectedToBorderButKeepsText() {
        val width = 100
        val height = 70
        val binary = IntArray(width * height) { 255 }
        for (y in 0 until height) for (x in 0 until 22) binary[y * width + x] = 0
        for (y in 30..33) for (x in 40..75) binary[y * width + x] = 0

        val cleaned = clearLargeBorderShadows(binary, width, height)

        assertTrue((0 until height).all { cleaned[it * width + 5] == 255 })
        assertTrue((40..75).all { cleaned[31 * width + it] == 0 })
    }
}
