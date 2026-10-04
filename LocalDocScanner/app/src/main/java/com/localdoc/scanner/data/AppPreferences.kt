package com.localdoc.scanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.localdoc.scanner.export.PdfExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One observable preference store shared by settings, library and export flows. */
class AppPreferences(context: Context) {
    val store: SharedPreferences = context.applicationContext.getSharedPreferences("app_defaults_v5", Context.MODE_PRIVATE)
    fun text(key: String, fallback: String = "") = store.getString(key, fallback) ?: fallback
    fun set(key: String, value: String) { store.edit().putString(key, value).apply() }
    val pageSize: PdfExporter.PageSize get() = runCatching { PdfExporter.PageSize.valueOf(text("paper", "A4")) }.getOrDefault(PdfExporter.PageSize.A4)
    val imageSide: Int get() = if (text("quality", "高清") == "标准") 2000 else 3200
    fun documentName(now: Long = System.currentTimeMillis()): String {
        val pattern = text("name", "拾页_{date}_{time}")
        return pattern.replace("{date}", SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(now)))
            .replace("{time}", SimpleDateFormat("HHmmss", Locale.CHINA).format(Date(now)))
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100).ifBlank { "拾页_$now" }
    }
}

@Composable
fun rememberPreference(key: String, fallback: String = ""): MutableState<String> {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val state = remember(key) { mutableStateOf(prefs.text(key, fallback)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed == key) state.value = prefs.text(key, fallback) }
        prefs.store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
