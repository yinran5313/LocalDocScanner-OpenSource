package com.localdoc.scanner.structure

import org.dhatim.fastexcel.Workbook
import java.io.File

/** Use fastexcel's Apache-2.0 Workbook/Worksheet implementation, retaining IDs as strings. */
object SpreadsheetExport {
    fun write(output: File, sheets: List<Pair<String, List<List<String>>>>) {
        require(sheets.isNotEmpty()) { "没有可导出的表格" }
        output.parentFile?.mkdirs()
        val part = File(output.parentFile, output.name + ".part")
        try {
            part.outputStream().buffered().use { stream ->
                Workbook(stream, "LocalDocScanner", "4.4").use { book ->
                    sheets.forEachIndexed { index, (name, rows) ->
                        val safeName = name.replace(Regex("[\\\\/\\[\\]:*?]"), "_").take(25).ifBlank { "表格" } + "${index + 1}"
                        val sheet = book.newWorksheet(safeName)
                        rows.forEachIndexed { r, cells -> cells.forEachIndexed { c, text ->
                            require(text.length <= 32767) { "第${r + 1}行第${c + 1}列超过Excel单元格上限" }
                            sheet.value(r, c, text)
                        } }
                        if (rows.isNotEmpty() && rows[0].isNotEmpty()) sheet.range(0, 0, 0, rows[0].lastIndex).style().bold().fillColor("DDEFE3").set()
                        (0 until (rows.maxOfOrNull { it.size } ?: 0)).forEach { sheet.width(it, 22.0) }
                    }
                }
            }
            check(part.length() > 0 && part.renameTo(output)) { "无法完成XLSX写入" }
        } finally { part.delete() }
    }
}
