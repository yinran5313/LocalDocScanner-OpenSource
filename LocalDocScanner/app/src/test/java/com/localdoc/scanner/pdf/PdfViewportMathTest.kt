package com.localdoc.scanner.pdf
import org.junit.Assert.*
import org.junit.Test
class PdfViewportMathTest {
    @Test fun bounds() { assertEquals(1f,PdfViewportMath.zoom(0f)); assertEquals(4f,PdfViewportMath.zoom(8f)); assertEquals(1f,PdfViewportMath.zoom(Float.NaN)); assertEquals(100f,PdfViewportMath.pan(300f,200f,2f)); assertEquals(0f,PdfViewportMath.pan(300f,200f,1f)) }
    @Test fun zoomPreservesAnchor() { assertEquals(700,PdfViewportMath.offset(200,2f,300f,0f)); assertEquals(680,PdfViewportMath.offset(200,2f,300f,20f)); assertEquals(0,PdfViewportMath.offset(0,0.5f,300f,0f)) }
}
