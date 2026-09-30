package com.localdoc.scanner

import com.localdoc.scanner.cv.detectDocumentQuad
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDetectionTest {
    @Test
    fun connectedRectangleWinsOverSeparateNoise() {
        val width = 200
        val height = 160
        val edges = IntArray(width * height)
        fun mark(x: Int, y: Int) { edges[y * width + x] = 255 }
        for (x in 35..170) { mark(x, 25); mark(x, 135) }
        for (y in 25..135) { mark(35, y); mark(170, y) }
        for (i in 0 until 25) mark(10 + i, 145 - i / 2)

        val quad = detectDocumentQuad(edges, width, height)
        assertNotNull(quad)
        assertTrue(quad!!.p0.x in 30f..42f)
        assertTrue(quad.p0.y in 20f..32f)
        assertTrue(quad.p2.x in 164f..176f)
        assertTrue(quad.p2.y in 129f..141f)
    }
}
