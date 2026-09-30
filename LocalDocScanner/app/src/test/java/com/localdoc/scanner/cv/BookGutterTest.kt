package com.localdoc.scanner.cv

import org.junit.Assert.*
import org.junit.Test

class BookGutterTest {
    @Test fun offCenterGutterDoesNotAlwaysSplitAtMiddle() {
        val width = 400; val height = 180
        val gray = IntArray(width * height) { if (it % width in 157..163) 70 else 235 }
        val result = BookGutter.estimateLuma(gray, width, height)
        assertTrue(result.confident)
        assertEquals(.4f, result.ratio, .012f)
    }
    @Test fun noVisibleGutterReturnsHonestLowConfidence() {
        val result = BookGutter.estimateLuma(IntArray(400 * 180) { 240 }, 400, 180)
        assertFalse(result.confident)
        assertEquals(.5f, result.ratio, 0f)
    }
}
