package com.localdoc.scanner.data

import android.content.Context
import org.json.JSONArray

/** Named library folders are metadata; an empty folder need not own a physical directory. */
object LibraryFolders {
    fun validate(name: String): String = name.trim().also { require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) { "文件夹名称限80字，不能含控制字符" } }
    fun all(context: Context): List<String> = runCatching {
        val array = JSONArray(context.getSharedPreferences("library_folders", 0).getString("names", "[]"))
        (0 until array.length()).map { array.getString(it) }.distinct().sorted()
    }.getOrDefault(emptyList())
    @Synchronized fun add(context: Context, name: String) {
        val value = validate(name)
        replace(context, all(context) + value)
    }
    @Synchronized fun replace(context: Context, names: List<String>) {
        check(context.getSharedPreferences("library_folders", 0).edit().putString("names", JSONArray(names.map(::validate).distinct()).toString()).commit()) { "文件夹信息保存失败" }
    }
}
