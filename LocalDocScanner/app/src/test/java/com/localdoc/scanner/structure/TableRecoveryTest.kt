package com.localdoc.scanner.structure

import com.localdoc.scanner.ocr.OcrTextBox
import org.junit.Assert.*
import org.junit.Test

class TableRecoveryTest {
    @Test fun retainsBlankCellAndGroupsSlightlyMisalignedRows() {
        val boxes = listOf(OcrTextBox("商品", .1f, .1f, .2f, .14f), OcrTextBox("金额", .6f, .105f, .7f, .145f),
            OcrTextBox("12.50", .6f, .3f, .7f, .34f))
        assertEquals(listOf(listOf("商品", "金额"), listOf("", "12.50")), TableRecovery.fromBoxes(boxes))
    }
    @Test fun manualTabularTextKeepsEmptyCells() {
        assertEquals(listOf(listOf("A", "", "C")), TableRecovery.fromText("A\t\tC"))
    }
    @Test fun receiptKeepsTotalAndNumberForReview() {
        val fields = ReceiptExtractor.fields("便利店\n2026-10-04\n票号：000123\n合计：￥25.50\n实付：25.00")
        assertEquals("25.00", fields["金额"])
        assertEquals("000123", fields["票号"])
    }
}
