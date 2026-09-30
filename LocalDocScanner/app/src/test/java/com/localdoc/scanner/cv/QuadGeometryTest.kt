package com.localdoc.scanner.cv

import org.junit.Assert.*
import org.junit.Test

class QuadGeometryTest {
    @Test fun diamondProducesFourDistinctCorners() {
        val result = orderedQuad(listOf(Point(100f, 10f), Point(190f, 100f), Point(100f, 190f), Point(10f, 100f)))!!
        assertEquals(4, result.toList().distinct().size)
        assertTrue(validDocumentQuad(result, 200, 200))
    }
    @Test fun clippedAndDegenerateContoursAreRejected() {
        val clipped = Quad(Point(0f, 20f), Point(150f, 20f), Point(150f, 180f), Point(0f, 180f))
        assertFalse(validDocumentQuad(clipped, 200, 200))
        assertNull(orderedQuad(List(4) { Point(10f, 10f) }))
        assertNull(orderedQuad(listOf(Point(Float.NaN, 0f), Point(1f, 2f), Point(3f, 4f), Point(5f, 6f))))
    }
}
