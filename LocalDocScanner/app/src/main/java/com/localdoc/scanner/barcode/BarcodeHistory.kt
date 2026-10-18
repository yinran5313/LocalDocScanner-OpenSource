package com.localdoc.scanner.barcode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BarcodeRecord(val text: String, val format: String, val time: Long, val name: String = "")
object BarcodeHistory {
    private const val NAME = "barcode_history"
    fun all(context: Context): List<BarcodeRecord> = runCatching {
        val array = JSONArray(context.getSharedPreferences(NAME, 0).getString("records", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { v -> BarcodeRecord(v.getString("text"), v.getString("format"), v.getLong("time"),v.optString("name")) } }
    }.getOrDefault(emptyList())
    @Synchronized fun add(context: Context, text: String, format: String) {
        val value=text.take(8000);val existing=all(context)
        val records = listOf(BarcodeRecord(value, format, System.currentTimeMillis(),existing.firstOrNull {it.text==value}?.name.orEmpty())) + existing.filterNot { it.text == value }
        write(context,records)
    }
    @Synchronized fun rename(context:Context,text:String,name:String) {
        write(context,all(context).map {if(it.text==text) it.copy(name=name.trim().take(80)) else it})
    }
    private fun write(context:Context,records:List<BarcodeRecord>) {
        val array = JSONArray(); records.take(100).forEach { array.put(JSONObject().put("text", it.text).put("format", it.format).put("time", it.time).put("name",it.name)) }
        context.getSharedPreferences(NAME, 0).edit().putString("records", array.toString()).apply()
    }
    fun clear(context: Context) { context.getSharedPreferences(NAME, 0).edit().remove("records").apply() }
}
