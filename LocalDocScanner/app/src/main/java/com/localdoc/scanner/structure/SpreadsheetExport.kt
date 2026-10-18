package com.localdoc.scanner.structure

import org.dhatim.fastexcel.Workbook
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            repairDimensions(part, sheets)
            check(part.length() > 0) { "无法完成XLSX写入" }
        }
    }

    /** fastexcel 0.18.4 streams a placeholder A1 dimension. Some readers trust it.
     * Rewrite only the small XML header; stream all cell data unchanged. */
    private fun repairDimensions(workbook: File, sheets: List<TableSheet>) {
        val dimensions = sheets.mapIndexed { index, table ->
            val lastRow = maxOf(table.rows.lastIndex, table.merges.maxOfOrNull { it.bottom } ?: 0)
            val lastCol = maxOf((table.rows.maxOfOrNull { it.size } ?: 1) - 1, table.merges.maxOfOrNull { it.right } ?: 0)
            var col = lastCol.coerceAtLeast(0) + 1
            var letters = ""
            while (col > 0) { col--; letters = ('A' + col % 26) + letters; col /= 26 }
            "xl/worksheets/sheet${index + 1}.xml" to "A1:$letters${lastRow.coerceAtLeast(0) + 1}"
        }.toMap()
        AtomicFiles.write(workbook) { patched ->
            ZipFile(workbook).use { source ->
                ZipOutputStream(patched.outputStream().buffered()).use { target ->
                    source.entries().asSequence().forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { input ->
                            val range = dimensions[entry.name]
                            if (range != null) {
                                // The writer emits ASCII before sheetData. Bound memory independently of table size.
                                val prefix = ByteArray(4096)
                                var count = 0
                                while (count < prefix.size) {
                                    val read = input.read(prefix, count, prefix.size - count)
                                    if (read < 0) break
                                    count += read
                                }
                                val header = String(prefix, 0, count, Charsets.ISO_8859_1)
                                val dimension = Regex("<dimension\\s+ref=\"[^\"]*\"\\s*/>")
                                check(dimension.containsMatchIn(header)) { "工作表范围头缺失，未替换原文件" }
                                target.write(header.replaceFirst(dimension, "<dimension ref=\"$range\"/>").toByteArray(Charsets.ISO_8859_1))
                            }
                            input.copyTo(target)
                        }
                        target.closeEntry()
                    }
                }
            }
        }
    }
}
