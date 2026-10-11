package com.localdoc.scanner.structure

data class CellMerge(val top: Int, val left: Int, val bottom: Int, val right: Int) {
    fun overlaps(other: CellMerge) = top <= other.bottom && bottom >= other.top && left <= other.right && right >= other.left
}
data class TableSheet(val name: String, val rows: List<List<String>>, val merges: List<CellMerge> = emptyList(), val columnTypes: Map<Int, String> = emptyMap())

object TableLayout {
    fun validate(rows: List<List<String>>, merges: List<CellMerge>) {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        merges.forEachIndexed { i, m ->
            require(m.top in rows.indices && m.bottom in m.top until rows.size && m.left in 0 until columns && m.right in m.left until columns && (m.top != m.bottom || m.left != m.right)) { "合并范围无效" }
            require(merges.take(i).none { it.overlaps(m) }) { "合并范围不能重叠" }
        }
    }
    fun combine(sheets: List<TableSheet>, skipHeader: Boolean): TableSheet {
        require(sheets.isNotEmpty())
        val width = sheets.first().rows.maxOfOrNull { it.size } ?: 0
        require(sheets.all { (it.rows.maxOfOrNull { row -> row.size } ?: 0) == width }) { "跨页表格列数不一致，请先调整" }
        require(sheets.all { it.columnTypes == sheets.first().columnTypes }) { "跨页列类型不一致，请先统一每页列类型" }
        val rows = mutableListOf<List<String>>()
        val merges = mutableListOf<CellMerge>()
        sheets.forEachIndexed { index, sheet ->
            validate(sheet.rows, sheet.merges)
            val drop = if (index > 0 && skipHeader && sheet.rows.firstOrNull() == rows.firstOrNull()) 1 else 0
            require(sheet.merges.none { it.top < drop }) { "重复表头含合并格，请关闭去重或先拆分表头" }
            val offset = rows.size - drop
            merges += sheet.merges.map { it.copy(top = it.top + offset, bottom = it.bottom + offset) }
            rows += sheet.rows.drop(drop)
        }
        return TableSheet("跨页合并", rows, merges, sheets.first().columnTypes)
    }
}
