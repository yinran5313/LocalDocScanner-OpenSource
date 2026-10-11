package com.localdoc.scanner.structure

import org.dhatim.fastexcel.Workbook
import java.io.File
import com.localdoc.scanner.util.AtomicFiles

/** Use fastexcel's Apache-2.0 Workbook/Worksheet implementation, retaining IDs as strings. */
object SpreadsheetExport {
    fun write(output: File, sheets: List<Pair<String, List<List<String>>>>) {
        writeTables(output, sheets.map { TableSheet(it.first, it.second) })
    }
    fun writeTables(output: File, sheets: List<TableSheet>) {
        require(sheets.isNotEmpty()) { "没有可导出的表格" }
        AtomicFiles.write(output) { part ->
            part.outputStream().buffered().use { stream ->
                Workbook(stream, "拾页", "5.0").use { book ->
                    sheets.forEachIndexed { index, table ->
                        val name = table.name; val rows = table.rows
                        TableLayout.validate(rows, table.merges)
                        val safeName = name.replace(Regex("[\\\\/\\[\\]:*?]"), "_").take(25).ifBlank { "表格" } + "${index + 1}"
                        val sheet = book.newWorksheet(safeName)
                        rows.forEachIndexed { r, cells -> cells.forEachIndexed { c, text ->
                            require(text.length <= 32767) { "第${r + 1}行第${c + 1}列超过Excel单元格上限" }
                            val merge = table.merges.firstOrNull { r in it.top..it.bottom && c in it.left..it.right }
                            if (merge == null) {
                                val number = text.trim().removeSuffix("%").replace(",", "").toBigDecimalOrNull()
                                when {
                                    r > 0 && table.columnTypes[c] in setOf("NUMBER", "PERCENT") && number != null && number.precision() <= 15 -> {
                                        sheet.value(r,c,if(table.columnTypes[c]=="PERCENT") number.toDouble()/100 else number.toDouble())
                                        if(table.columnTypes[c]=="PERCENT") sheet.style(r,c).format("0.00%").set()
                                    }
                                    r > 0 && table.columnTypes[c]=="DATE" && runCatching { java.time.LocalDate.parse(text.trim().replace('/','-')) }.isSuccess -> {
                                        sheet.value(r,c,java.time.LocalDate.parse(text.trim().replace('/','-')))
                                        sheet.style(r,c).format("yyyy-mm-dd").set()
                                    }
                                    else -> sheet.value(r,c,text)
                                }
                            } else if(r==merge.top && c==merge.left) {
                                val mergedText=(merge.top..merge.bottom).flatMap { rr -> (merge.left..merge.right).map { cc -> rows[rr].getOrElse(cc) { "" } } }.filter(String::isNotBlank).joinToString("\n")
                                require(mergedText.length<=32767) { "合并格超过Excel字符上限" }
                                sheet.value(r,c,mergedText)
                            }
                        } }
                        table.merges.forEach { m -> sheet.range(m.top,m.left,m.bottom,m.right).merge() }
                        if (rows.isNotEmpty() && rows[0].isNotEmpty()) sheet.range(0, 0, 0, rows[0].lastIndex).style().bold().fillColor("DDEFE3").set()
                        (0 until (rows.maxOfOrNull { it.size } ?: 0)).forEach { sheet.width(it, 22.0) }
                    }
                }
            }
            check(part.length() > 0) { "无法完成XLSX写入" }
        }
    }
}
