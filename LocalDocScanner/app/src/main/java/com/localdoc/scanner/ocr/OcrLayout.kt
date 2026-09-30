package com.localdoc.scanner.ocr

import org.json.JSONArray
import org.json.JSONObject

/** Normalized positions in the exact bitmap sent to OCR, independent of decoded resolution. */
data class OcrTextBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

object OcrLayout {
    fun encode(lines: List<OcrTextBox>): String = JSONArray().also { array ->
        lines.forEach { array.put(JSONObject().put("text", it.text).put("left", it.left.toDouble())
            .put("top", it.top.toDouble()).put("right", it.right.toDouble()).put("bottom", it.bottom.toDouble())) }
    }.toString()

    fun decode(raw: String): List<OcrTextBox> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val value = array.getJSONObject(i)
            val box = OcrTextBox(value.getString("text"), value.getDouble("left").toFloat(), value.getDouble("top").toFloat(),
                value.getDouble("right").toFloat(), value.getDouble("bottom").toFloat())
            box.takeIf { valid(it) }
        }
    }.getOrDefault(emptyList())

    fun valid(box: OcrTextBox): Boolean = box.text.isNotBlank() &&
        listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() && it in 0f..1f } &&
        box.left < box.right && box.top < box.bottom
}
