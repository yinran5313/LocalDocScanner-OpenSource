package com.localdoc.scanner.external

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalFile(val uri: Uri, val mime: String, val name: String)

object ExternalOpenBus {
    private val _current = MutableStateFlow<ExternalFile?>(null)
    val current = _current.asStateFlow()

    fun offer(intent: Intent?, resolver: ContentResolver) {
        if (intent == null || intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND)) return
        @Suppress("DEPRECATION")
        val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val mime = intent.type ?: runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "外部文件"
        _current.value = ExternalFile(uri, mime, com.localdoc.scanner.util.DisplayNames.readable(name))
    }

    fun close() {
        _current.value = null
    }
}
