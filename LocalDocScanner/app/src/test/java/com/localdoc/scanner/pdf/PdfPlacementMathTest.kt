package com.localdoc.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPlacementMathTest {
    @Test
    fun centeredPlacementAndMovementStayInsidePage() {
        val atCorner = PdfPlacementMath.centeredAt(0f, 0f, 0.4f, 0.2f)
        assertEquals(0f, atCorner.x, 0.0001f)
        assertEquals(0f, atCorner.y, 0.0001f)

        val moved = PdfPlacementMath.move(atCorner, 2f, 2f)
        assertEquals(0.6f, moved.x, 0.0001f)
        assertEquals(0.8f, moved.y, 0.0001f)
    }

    @Test
    fun resizeNeverCrossesPageBoundaryOrMinimumSize() {
        val source = NormalizedRect(0.75f, 0.8f, 0.2f, 0.15f)
        val grown = PdfPlacementMath.resize(source, 1f, 1f)
        assertEquals(0.25f, grown.width, 0.0001f)
        assertEquals(0.2f, grown.height, 0.0001f)

        val shrunk = PdfPlacementMath.resize(source, -1f, -1f)
        assertTrue(shrunk.width >= 0.04f)
        assertTrue(shrunk.height >= 0.025f)
    }
}
