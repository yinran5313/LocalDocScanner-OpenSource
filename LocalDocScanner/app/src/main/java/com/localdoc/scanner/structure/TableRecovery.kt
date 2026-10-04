package com.localdoc.scanner.structure

import com.localdoc.scanner.ocr.OcrTextBox
import kotlin.math.abs

/** Geometry suggests rows/columns; users review cells before export. No invented missing cells. */
object TableRecovery {
    fun fromBoxes(boxes: List<OcrTextBox>): List<List<String>> {
        if (boxes.isEmpty()) return emptyList()
        val tolerance = boxes.map { it.bottom - it.top }.sorted()[boxes.size / 2].coerceAtLeast(0.005f) * 0.65f
        val rows = mutableListOf<MutableList<OcrTextBox>>()
        boxes.sortedBy { (it.top + it.bottom) / 2 }.forEach { box ->
            val center = (box.top + box.bottom) / 2
            val row = rows.lastOrNull()
            if (row != null && abs(center - row.map { (it.top + it.bottom) / 2 }.average()) <= tolerance) row.add(box)
            else rows.add(mutableListOf(box))
        }
        val anchors = mutableListOf<Float>()
        rows.maxByOrNull { it.size }?.sortedBy { it.left }?.forEach { anchors.add(it.left) }
        return rows.map { row ->
            val cells = MutableList(anchors.size) { "" }
            row.sortedBy { it.left }.forEach { box ->
                val column = anchors.indices.minByOrNull { abs(anchors[it] - box.left) } ?: 0
                cells[column] = listOf(cells[column], box.text).filter(String::isNotBlank).joinToString(" ")
            }
            cells
        }
    }
    fun fromText(text: String): List<List<String>> = text.lineSequence().filter(String::isNotBlank).map { line ->
        if ('\t' in line) line.split('\t') else line.split(Regex(" {2,}|[|｜]"))
    }.toList()
}
