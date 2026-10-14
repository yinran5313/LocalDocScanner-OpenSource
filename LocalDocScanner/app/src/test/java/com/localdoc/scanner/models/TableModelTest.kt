package com.localdoc.scanner.models

import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.structure.CellMerge
import org.junit.Test
import org.junit.Assert.*

class TableModelTest {
    @Test fun mergedStructureAssignsWordsOnceAndReservesRowsForRowSpans() {
        val tokens = listOf("<tr>", "<td", " rowspan=\"2\"", ">", "</td>", "<td></td>", "</tr>", "<tr>", "<td></td>", "</tr>")
        val bounds = listOf(floatArrayOf(0f,0f,.5f,1f), floatArrayOf(.5f,0f,1f,.5f), floatArrayOf(.5f,.5f,1f,1f))
        val words = listOf(OcrTextBox("编号", .1f,.1f,.3f,.2f), OcrTextBox("金额", .6f,.1f,.8f,.2f), OcrTextBox("12.50", .6f,.6f,.8f,.7f))
        val result = TableModel.assemble(tokens, bounds, words, .9f)
        assertEquals(listOf(listOf("编号","金额"),listOf("","12.50")), result.rows)
        assertEquals(listOf(CellMerge(0,0,1,0)), result.merges)
    }
}
