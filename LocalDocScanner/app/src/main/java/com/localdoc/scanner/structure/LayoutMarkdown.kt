package com.localdoc.scanner.structure

import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.ocr.OcrLayout

/** Deterministic layout hints; source words remain editable and never invent missing characters. */
object LayoutMarkdown {
    fun ordered(input: List<OcrTextBox>, columns: String = "AUTO"): List<OcrTextBox> {
        val boxes = input.filter(OcrLayout::valid).sortedWith(compareBy({ it.top }, { it.left }))
        val left = boxes.filter { it.right < .48f }; val right = boxes.filter { it.left > .52f }
        val two = columns == "TWO" || columns == "AUTO" && left.size >= 4 && right.size >= 4 &&
            minOf(left.maxOf { it.bottom },right.maxOf { it.bottom }) - maxOf(left.minOf { it.top },right.minOf { it.top }) > .2f
        if (!two) return boxes
        val spans = boxes.filter { it.left < .4f && it.right > .6f }
        var remaining = boxes - spans.toSet()
        val ordered = mutableListOf<OcrTextBox>()
        fun band(values: List<OcrTextBox>) { ordered += values.filter { (it.left+it.right)/2 < .5f }.sortedBy { it.top }; ordered += values.filter { (it.left+it.right)/2 >= .5f }.sortedBy { it.top } }
        spans.forEach { span -> val before = remaining.filter { (it.top+it.bottom)/2 < (span.top+span.bottom)/2 }; band(before); remaining = remaining-before.toSet(); ordered += span }
        band(remaining); return ordered
    }
    fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val columns = rows.maxOf { it.size }
        fun row(cells: List<String>) = "| " + (0 until columns).joinToString(" | ") { cells.getOrElse(it) { "" }.replace("|","\\|").replace("\n"," ") } + " |"
        return (listOf(row(rows.first()),row(List(columns) { "---" })) + rows.drop(1).map(::row)).joinToString("\n")
    }
    fun escape(value: String): String = value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;").replace("\r", " ").replace("\n", " ")
    fun modelTable(model: com.localdoc.scanner.models.ModelTable): String = table(model.rows) + if(model.merges.isNotEmpty()) "\n\n<!-- 合并单元格结构保留在本页来源JSON中 -->" else ""
    fun render(input: List<OcrTextBox>, columns: String = "AUTO"): String {
        val boxes = ordered(input,columns)
        if (boxes.isEmpty()) return "（未识别到文字，请对照原图补充）"
        val median = boxes.map { it.bottom - it.top }.sorted()[boxes.size / 2]
        return boxes.joinToString("\n\n") { box ->
            val text = escape(box.text)
            when {
                box.bottom - box.top > median * 1.45f && box.text.length <= 50 -> "### $text"
                box.text.matches(Regex("^[•●·].*")) -> "- " + text.drop(1).trim()
                box.text.matches(Regex("^[0-9]+[.)、].*")) -> text.replaceFirst(Regex("^([0-9]+)[.)、]"), "$1. ")
                else -> text.replace(Regex("^([#>])"), "\\\\$1")
            }
        }
    }
}
