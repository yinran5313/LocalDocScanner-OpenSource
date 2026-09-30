package com.localdoc.scanner.output

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.localdoc.scanner.office.OfficeFormats

data class OutputRecord(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val createdAt: Long,
    val internalPath: String,
    val savedUri: String = "",
    val savedLabel: String = ""
)

object OutputHistoryStore {
    private const val PREFS = "output_history"
    private const val KEY = "records"
    private const val LIMIT = 100
    private val lock = Any()

    fun all(context: Context): List<OutputRecord> = synchronized(lock) {
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty())
            .sortedByDescending { it.createdAt }
    }

    fun recordGenerated(context: Context, file: File, mime: String): OutputRecord = synchronized(lock) {
        val current = allUnlocked(context).toMutableList()
        val existing = current.indexOfFirst { it.internalPath == file.absolutePath }
        val record = OutputRecord(
            id = if (existing >= 0) current[existing].id else UUID.randomUUID().toString(),
            name = file.name,
            mime = mime,
            size = file.length(),
            createdAt = System.currentTimeMillis(),
            internalPath = file.absolutePath,
            savedUri = if (existing >= 0) current[existing].savedUri else "",
            savedLabel = if (existing >= 0) current[existing].savedLabel else ""
        )
        if (existing >= 0) current.removeAt(existing)
        current.add(0, record)
        write(context, current.take(LIMIT))
        record
    }

    fun markSaved(context: Context, file: File, uri: Uri, label: String): OutputRecord = synchronized(lock) {
        val current = allUnlocked(context).toMutableList()
        val index = current.indexOfFirst { it.internalPath == file.absolutePath }
        val base = if (index >= 0) current.removeAt(index) else OutputRecord(
            UUID.randomUUID().toString(), file.name, mimeFor(file), file.length(), System.currentTimeMillis(), file.absolutePath
        )
        val updated = base.copy(
            name = displayName(context, uri).ifBlank { file.name },
            size = file.length(),
            savedUri = uri.toString(),
            savedLabel = label,
            createdAt = System.currentTimeMillis()
        )
        current.add(0, updated)
        write(context, current.take(LIMIT))
        updated
    }

    fun recordDirectSaved(context: Context, name: String, mime: String, uri: Uri, label: String): OutputRecord = synchronized(lock) {
        val record = OutputRecord(
            id = UUID.randomUUID().toString(),
            name = displayName(context, uri).ifBlank { name },
            mime = mime,
            size = querySize(context, uri),
            createdAt = System.currentTimeMillis(),
            internalPath = "",
            savedUri = uri.toString(),
            savedLabel = label
        )
        val current = allUnlocked(context).toMutableList().apply { add(0, record) }
        write(context, current.take(LIMIT))
        record
    }

    fun describeDestination(context: Context, uri: Uri): String {
        val provider = uri.authority?.let { authority ->
            context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString()
        }.orEmpty()
        val name = displayName(context, uri)
        return listOf(provider, name).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "系统选择的位置" }
    }

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun allUnlocked(context: Context): List<OutputRecord> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty())

    private fun write(context: Context, records: List<OutputRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("mime", r.mime); put("size", r.size)
                put("createdAt", r.createdAt); put("internalPath", r.internalPath)
                put("savedUri", r.savedUri); put("savedLabel", r.savedLabel)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun decode(value: String): List<OutputRecord> = runCatching {
        val array = JSONArray(value.ifBlank { "[]" })
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(OutputRecord(
                    id = o.optString("id"), name = o.optString("name"), mime = o.optString("mime", "*/*"),
                    size = o.optLong("size"), createdAt = o.optLong("createdAt"),
                    internalPath = o.optString("internalPath"), savedUri = o.optString("savedUri"),
                    savedLabel = o.optString("savedLabel")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        else -> OfficeFormats.mimeFor(file.name)
    }
}
