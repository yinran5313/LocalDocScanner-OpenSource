package com.localdoc.scanner.structure

import com.localdoc.scanner.models.TableCell
import com.localdoc.scanner.ocr.OcrTextBox
import org.junit.Assert.*
import org.junit.Test

class RegionCoordinatesTest {
    @Test fun fiveColumnsKeepTheirOriginalPagePositionsAfterCrop() {
        val crop=RegionCoordinates(LayoutRegion("table",.9f,.1f,.2f,.9f,.8f),1000,2000)
        repeat(5) { column ->
            val local=TableCell(0,column,1,1,column*.2f,0f,(column+1)*.2f,1f)
            val page=crop.page(local)
            val word=crop.local(OcrTextBox("列$column",page.left,page.top,page.right,page.bottom))
            assertEquals(local.left,word.left,.0001f); assertEquals(local.right,word.right,.0001f)
            assertEquals(.2f,page.top,.0001f); assertEquals(.8f,page.bottom,.0001f)
        }
    }
    @Test fun pixelRoundingAndWordsOutsideCropCannotProduceInvalidBounds() {
        val crop=RegionCoordinates(LayoutRegion("table",1f,.333f,.111f,.999f,.999f),31,43)
        assertTrue(crop.width>0 && crop.left+crop.width<=31)
        val word=crop.local(OcrTextBox("边缘",0f,0f,1f,1f))
        assertEquals(0f,word.left,0f); assertEquals(1f,word.right,0f)
    }
}
