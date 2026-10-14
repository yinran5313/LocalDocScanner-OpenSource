package com.localdoc.scanner.structure

import org.junit.Assert.*
import org.junit.Test

class DocumentLayoutTest {
    @Test fun threeColumnsReadDownBeforeNextColumnWithFullWidthHeading() {
        val heading=LayoutRegion("title",1f,.1f,.02f,.9f,.09f)
        val body=(0..2).flatMap { column -> (0..3).map { row -> LayoutRegion("text",1f,.03f+column*.33f,.18f+row*.09f,.29f+column*.33f,.23f+row*.09f) } }
        val ordered=DocumentReadingOrder.order((body+heading).reversed()) { it }
        assertEquals(heading,ordered.first()); assertEquals(body,ordered.drop(1))
    }
    @Test fun fullWidthFigureSeparatesTwoReadingBands() {
        val left=LayoutRegion("text",1f,.1f,.1f,.4f,.2f); val right=LayoutRegion("text",1f,.6f,.1f,.9f,.2f)
        val figure=LayoutRegion("figure",1f,.1f,.3f,.9f,.5f)
        val after=left.copy(top=.6f,bottom=.7f)
        assertEquals(listOf(left,right,figure,after),DocumentReadingOrder.order(listOf(after,figure,right,left)) { it })
    }
    @Test fun decoderRejectsWrongTensorDimensionsAndNonfiniteRegions() {
        assertThrows(IllegalArgumentException::class.java) { PicoLayoutDecode.decode(List(8) { FloatArray(1) }) }
        assertTrue(DocumentReadingOrder.order(listOf(LayoutRegion("text",1f,Float.NaN,0f,1f,1f))) { it }.isEmpty())
    }
    @Test fun decoderUnderstandsDflDistancesAndSuppressesDuplicateClassBoxes() {
        val outputs=(0..3).map { FloatArray(((608+(8 shl it)-1)/(8 shl it))*((800+(8 shl it)-1)/(8 shl it))*10) }+
            (0..3).map { FloatArray(((608+(8 shl it)-1)/(8 shl it))*((800+(8 shl it)-1)/(8 shl it))*32) }
        val index=50*76+38
        outputs[0][index*10+2]=.9f
        for(d in 0..3) outputs[4][index*32+d*8+4]=10f
        val found=PicoLayoutDecode.decode(outputs)
        assertEquals(1,found.size); assertEquals("figure",found.single().kind); assertTrue(found.single().valid)
        assertEquals(64f/608,(found.single().right-found.single().left),.001f)
    }
    @Test fun ocrMarkdownCannotIntroduceLocalImagesOrRawHtml() {
        val safe=LayoutMarkdown.escape("![x](private.jpg)<script>*")
        assertTrue(safe.contains("\\[")); assertFalse(safe.contains("<script>")); assertTrue(safe.endsWith("\\*"))
    }
}
