package com.localdoc.scanner.models

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.structure.CellMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

data class TableCell(val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float)
data class ModelTable(val rows: List<List<String>>, val merges: List<CellMerge>, val cells: List<TableCell>, val score: Float)

/** PaddleX TableLabelDecode and PP-OCR table preprocessing, Apache-2.0; OCR words are assigned once by intersection. */
object TableModel {
    suspend fun recover(context: Context, bitmap: Bitmap, words: List<OcrTextBox>): ModelTable = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        val dictText = ModelCatalog.file(context, "table", "inference.yml").readText().substringAfter("character_dict:\n")
        val dict = dictText.lineSequence().filter { it.trim().startsWith("- ") }.map { line ->
            val raw = line.trim().removePrefix("- ")
            if (raw.startsWith("'") && raw.endsWith("'")) raw.substring(1, raw.length - 1) else raw
        }.toMutableList()
        dict.remove("<td>"); if ("<td></td>" !in dict) dict += "<td></td>"
        val labels = listOf("sos") + dict + "eos"
        require(labels.size == 50) { "表格字典与模型版本不匹配" }
        val ratio = 488f / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * ratio).roundToInt().coerceIn(1, 488); val h = (bitmap.height * ratio).roundToInt().coerceIn(1, 488)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val input = FloatArray(3 * 488 * 488)
        try {
            val colors = IntArray(w * h); scaled.getPixels(colors, 0, w, 0, 0, w, h)
            val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
            colors.forEachIndexed { i, c ->
                val destination = i / w * 488 + i % w
                val values = intArrayOf(Color.blue(c), Color.green(c), Color.red(c))
                for (channel in 0..2) input[channel * 488 * 488 + destination] = (values[channel] / 255f - mean[channel]) / std[channel]
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
        ModelInference.session(ModelCatalog.file(context, "table", "inference.onnx")).use { session ->
            ModelInference.floats(input, longArrayOf(1, 3, 488, 488)).use { x ->
                session.run(mapOf("x" to x)).use { output ->
                    val bounds = ModelInference.copy(output[0] as OnnxTensor); val probabilities = ModelInference.copy(output[1] as OnnxTensor)
                    require(probabilities.size % labels.size == 0 && bounds.size / 8 == probabilities.size / labels.size)
                    val tokens = mutableListOf<String>(); val cellBounds = mutableListOf<FloatArray>(); val scores = mutableListOf<Float>()
                    for (step in 0 until probabilities.size / labels.size) {
                        val index = labels.indices.maxBy { probabilities[step * labels.size + it] }
                        val token = labels[index]; if (token == "eos") break; if (token == "sos") continue
                        tokens += token; scores += probabilities[step * labels.size + index]
                        if (token in listOf("<td", "<td>", "<td></td>")) {
                            val b = bounds.copyOfRange(step * 8, step * 8 + 8)
                            cellBounds += floatArrayOf((0..3).minOf { b[it * 2] } * 488 / w, (0..3).minOf { b[it * 2 + 1] } * 488 / h,
                                (0..3).maxOf { b[it * 2] } * 488 / w, (0..3).maxOf { b[it * 2 + 1] } * 488 / h).map { it.coerceIn(0f, 1f) }.toFloatArray()
                        }
                    }
                    val score = scores.average().toFloat()
                    require(score.isFinite() && score >= .5f) { "表格结构置信度较低，请使用基础行列复核" }
                    assemble(tokens, cellBounds, words, score)
                }
            }
        }
    } }

    internal fun assemble(tokens: List<String>, bounds: List<FloatArray>, words: List<OcrTextBox>, score: Float): ModelTable {
        val html = tokens.joinToString(""); val matches = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        require(matches.isNotEmpty() && matches.size <= 500) { "未恢复有效表格行" }
        var nextBounds = 0; val occupied = mutableSetOf<Pair<Int, Int>>(); val cells = mutableListOf<TableCell>()
        matches.forEachIndexed { row, match ->
            var column = 0
            Regex("<td([^>]*)>.*?</td>", RegexOption.DOT_MATCHES_ALL).findAll(match.groupValues[1]).forEach { td ->
                while (row to column in occupied) column++
                fun span(name: String) = Regex("$name=\"([0-9]+)\"").find(td.groupValues[1])?.groupValues?.get(1)?.toInt()?.coerceIn(1, 20) ?: 1
                val rowSpan = span("rowspan"); val columnSpan = span("colspan")
                require(nextBounds < bounds.size && column + columnSpan <= 200 && row + rowSpan <= 500) { "表格结构与位置不一致" }
                val b = bounds[nextBounds++]
                cells += TableCell(row, column, rowSpan, columnSpan, b[0], b[1], b[2], b[3])
                for (r in row until row + rowSpan) for (c in column until column + columnSpan) require(occupied.add(r to c)) { "模型合并格重叠，请手动复核" }
                column += columnSpan
            }
        }
        require(cells.isNotEmpty() && cells.size == bounds.size) { "未恢复有效单元格" }
        val texts = MutableList(cells.size) { mutableListOf<OcrTextBox>() }
        words.forEach { word ->
            val index = cells.indices.maxBy { i ->
                val c = cells[i]; maxOf(0f, minOf(word.right, c.right) - maxOf(word.left, c.left)) * maxOf(0f, minOf(word.bottom, c.bottom) - maxOf(word.top, c.top))
            }
            val c = cells[index]
            if (word.right > c.left && word.left < c.right && word.bottom > c.top && word.top < c.bottom) texts[index] += word
        }
        val columns = cells.maxOf { it.column + it.columnSpan }; val rows = MutableList(cells.maxOf { it.row + it.rowSpan }) { MutableList(columns) { "" } }
        cells.forEachIndexed { i, c -> rows[c.row][c.column] = texts[i].sortedWith(compareBy({ it.top }, { it.left })).joinToString(" ") { it.text } }
        val merges = cells.filter { it.rowSpan > 1 || it.columnSpan > 1 }.map { CellMerge(it.row, it.column, it.row + it.rowSpan - 1, it.column + it.columnSpan - 1) }
        return ModelTable(rows, merges, cells, score)
    }
}
