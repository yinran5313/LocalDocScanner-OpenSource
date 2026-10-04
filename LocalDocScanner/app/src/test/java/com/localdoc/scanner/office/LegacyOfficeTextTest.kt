package com.localdoc.scanner.office
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextBox
class LegacyOfficeTextTest {
    @Test fun extractsBinaryWord() {
        val file=File.createTempFile("legacy-word", ".doc")
        try {
            javaClass.getResourceAsStream("/legacy-simple.doc")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            assertTrue(LegacyOfficeText.read(file).contains("This is a simple file"))
        } finally { file.delete() }
    }
    @Test fun extractsBinarySpreadsheetAndPptWithoutRendering() {
        val dir=File("build/v5-fixtures").apply { mkdirs() }
        val sheet=File(dir,"legacy-sheet.xls")
        HSSFWorkbook().use { book -> book.createSheet("复核").createRow(0).createCell(0).setCellValue("拾页表格索引"); sheet.outputStream().use { book.write(it) } }
        assertTrue(LegacyOfficeText.read(sheet).contains("拾页表格索引"))
        val ppt=File(dir,"legacy-slides.ppt")
        HSLFSlideShow().use { show -> val slide=show.createSlide(); val box=HSLFTextBox(); box.text="拾页PPT索引"; slide.addShape(box); ppt.outputStream().use { show.write(it) } }
        assertTrue(LegacyOfficeText.read(ppt).contains("拾页PPT索引"))
    }
}
