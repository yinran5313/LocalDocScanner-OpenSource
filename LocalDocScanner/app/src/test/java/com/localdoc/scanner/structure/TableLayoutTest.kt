package com.localdoc.scanner.structure
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
class TableLayoutTest {
    @Test fun combinePreservesMergesAndOnlyExactHeaderDrops() {
        val a=TableSheet("一",listOf(listOf("名称","金额"),listOf("甲","10")))
        val b=TableSheet("二",listOf(listOf("名称","金额"),listOf("乙","20")),listOf(CellMerge(1,0,1,1)))
        val result=TableLayout.combine(listOf(a,b),true)
        assertEquals(3,result.rows.size)
        assertEquals(CellMerge(2,0,2,1),result.merges.single())
    }
    @Test fun invalidMergeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TableLayout.validate(listOf(listOf("a","b")),listOf(CellMerge(0,0,1,1))) }
    }
    @Test fun xlsxHasRealMergesAndTypedValuesButLongIdRemainsText() {
        val output=File.createTempFile("v5-xlsx", ".xlsx")
        try {
            SpreadsheetExport.writeTables(output,listOf(TableSheet("复核",listOf(listOf("名称","金额","号码"),listOf("甲","12.50","11010519491231002X"),listOf("合并","内容","123456789012345678")),listOf(CellMerge(2,0,2,1)),mapOf(1 to "NUMBER",2 to "NUMBER"))))
            ZipFile(output).use { zip -> val xml=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
                assertTrue(xml.contains("mergeCell ref=\"A3:B3\"")); assertTrue(xml.contains("12.5"))
                assertTrue(xml.contains("r=\"C3\" t=\"s\""))
            }
        } finally { output.delete() }
    }
}
