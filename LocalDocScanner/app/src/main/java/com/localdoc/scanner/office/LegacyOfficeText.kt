package com.localdoc.scanner.office

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.hslf.usermodel.HSLFSlideShowImpl
import org.apache.poi.hslf.record.Record
import org.apache.poi.hslf.record.RecordContainer
import org.apache.poi.hslf.record.TextBytesAtom
import org.apache.poi.hslf.record.TextCharsAtom
import org.apache.poi.hslf.record.PPDrawing
import org.apache.poi.ss.usermodel.DataFormatter
import java.io.File

/** Apache POI's binary text records; no desktop rendering/AWT calls on Android. */
object LegacyOfficeText {
    fun read(file: File, maxChars: Int = 2_000_000): String {
        val result = when(file.extension.lowercase()) {
            "doc" -> file.inputStream().use { input -> WordExtractor(input).use { it.text } }
            "xls" -> HSSFWorkbook(file.inputStream()).use { book ->
                val formatter = DataFormatter(java.util.Locale.CHINA)
                buildString { for (sheet in book) { appendLine(sheet.sheetName); for (row in sheet) {
                    for(cell in row) { append(formatter.formatCellValue(cell)); append('\t') }
                    appendLine(); if(length > maxChars) break
                }; if(length > maxChars) break } }
            }
            "ppt" -> file.inputStream().use { input -> HSLFSlideShowImpl(input).use { show ->
                buildString {
                    fun visit(record: Record) {
                        if(length > maxChars) return
                        when(record) {
                            is TextCharsAtom -> appendLine(record.text)
                            is TextBytesAtom -> appendLine(record.text)
                            is PPDrawing -> record.textboxWrappers.forEach(::visit)
                            is RecordContainer -> record.childRecords.forEach(::visit)
                        }
                    }
                    show.records.forEach(::visit)
                }
            } }
            else -> error("不是旧Office文件")
        }
        return result.take(maxChars)
    }
}
