package com.localdoc.scanner.external

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter

internal data class CsvDocument(val rows: List<List<String>>, val delimiter: Char,
    val separator: String, val bom: Boolean) {
    fun encode(newRows: List<List<String>>): String {
        val builder = StringBuilder()
        val format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setRecordSeparator(separator).build()
        CSVPrinter(builder, format).use { printer -> newRows.forEach { printer.printRecord(it) } }
        return (if (bom) "\uFEFF" else "") + builder.toString()
    }
    companion object {
        fun parse(text: String, delimiter: Char = ','): CsvDocument {
            require(text.length <= 2_000_000) { "表格预览限200万字符，可切换源码编辑" }
            val format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).build()
            val rows = CSVParser.parse(text.removePrefix("\uFEFF"), format).use { parser ->
                val result = mutableListOf<List<String>>()
                var cells = 0
                for (record in parser) {
                    cells += record.size()
                    require(record.size() <= 200 && cells <= 20_000) { "表格预览限200列、2万单元格，可切换源码编辑" }
                    result.add(record.toList())
                }
                result
            }
            return CsvDocument(rows, delimiter, if (text.contains("\r\n")) "\r\n" else "\n", text.startsWith("\uFEFF"))
        }
    }
}
