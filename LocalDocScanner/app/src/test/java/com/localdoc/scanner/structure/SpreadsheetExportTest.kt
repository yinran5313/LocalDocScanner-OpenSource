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
                assertNotNull(zip.getEntry("[Content_Types].xml"))
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
