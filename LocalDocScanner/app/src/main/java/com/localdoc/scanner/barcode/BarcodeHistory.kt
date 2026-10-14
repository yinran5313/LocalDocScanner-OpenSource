package com.localdoc.scanner.barcode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BarcodeRecord(val text: String, val format: String, val time: Long)
object BarcodeHistory {
    private const val NAME = "barcode_history"
    fun all(context: Context): List<BarcodeRecord> = runCatching {
        val array = JSONArray(context.getSharedPreferences(NAME, 0).getString("records", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { v -> BarcodeRecord(v.getString("text"), v.getString("format"), v.getLong("time")) } }
    }.getOrDefault(emptyList())
    @Synchronized fun add(context: Context, text: String, format: String) {
        val records = listOf(BarcodeRecord(text.take(8000), format, System.currentTimeMillis())) + all(context).filterNot { it.text == text }
        val array = JSONArray(); records.take(100).forEach { array.put(JSONObject().put("text", it.text).put("format", it.format).put("time", it.time)) }
        context.getSharedPreferences(NAME, 0).edit().putString("records", array.toString()).apply()
    }
    fun clear(context: Context) { context.getSharedPreferences(NAME, 0).edit().remove("records").apply() }
}
