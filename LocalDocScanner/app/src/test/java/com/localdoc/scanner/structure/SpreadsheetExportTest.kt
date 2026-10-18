package com.localdoc.scanner.structure

import com.localdoc.scanner.office.OpenXmlEditor
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class SpreadsheetExportTest {
    @Test fun exportedWorkbookPreservesChineseLeadingZerosAndFormulaLikeLiteral() {
        val dir = Files.createTempDirectory("xlsx-export").toFile()
        try {
            val output = java.io.File(dir, "票据.xlsx")
            SpreadsheetExport.write(output, listOf("发票" to listOf(listOf("号码", "购方", "备注"), listOf("00001234567890123456", "甲&乙<单位>", "=SUM(1,2)"))))
            val values = OpenXmlEditor.read(output).units.map { it.text }
            assertTrue(values.contains("00001234567890123456"))
            assertTrue(values.contains("甲&乙<单位>"))
            assertTrue(values.contains("=SUM(1,2)"))
            ZipFile(output).use { zip ->
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
                assertFalse(sheet.contains("<f>"))
                assertTrue(sheet.contains("<dimension ref=\"A1:C2\"/>"))
                assertNotNull(zip.getEntry("[Content_Types].xml"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun everySheetAdvertisesItsActualRaggedAndWideRange() {
        val dir = Files.createTempDirectory("xlsx-ranges").toFile()
        try {
            val output = java.io.File(dir, "ranges.xlsx")
            SpreadsheetExport.write(output, listOf(
                "收据" to listOf(listOf("商户", "日期", "金额", "票号"), listOf("甲", "2026-10-17", "40", "001")),
                "宽表" to listOf(listOf("标题"), List(28) { "列$it" }, listOf("尾")),
                "空表" to emptyList()))
            ZipFile(output).use { zip ->
                listOf("A1:D2", "A1:AB3", "A1:A1").forEachIndexed { i, range ->
                    val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet${i + 1}.xml")).bufferedReader().readText()
                    assertTrue(xml, xml.contains("<dimension ref=\"$range\"/>"))
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun oversizedCellFailsWithoutReplacingPreviousExport() {
        val dir = Files.createTempDirectory("xlsx-export").toFile()
        try {
            val output = java.io.File(dir, "old.xlsx").apply { writeText("previous") }
            assertThrows(IllegalArgumentException::class.java) { SpreadsheetExport.write(output, listOf("表" to listOf(listOf("a".repeat(32768))))) }
            assertEquals("previous", output.readText())
            assertFalse(java.io.File(dir, "old.xlsx.part").exists())
        } finally { dir.deleteRecursively() }
    }
}
