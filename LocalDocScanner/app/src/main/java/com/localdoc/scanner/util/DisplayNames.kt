package com.localdoc.scanner.util

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Some providers return percent-encoded UTF-8 filenames. Decode Unicode byte runs only;
 * preserve plus signs, literal ASCII escapes, malformed input and path separators. */
object DisplayNames {
    fun withMimeExtension(name: String, mime: String): String {
        val known=setOf("txt","csv","tsv","md","markdown","pdf","zip","png","jpg","jpeg","webp","bmp")
        if(name.substringAfterLast('.',"").lowercase() in known)return name
        val suffix=when(mime.substringBefore(';').lowercase()) {
            "text/markdown" -> "md"; "text/csv" -> "csv"; "text/tab-separated-values" -> "tsv"; "text/plain" -> "txt"
            "application/zip","application/x-zip-compressed" -> "zip"; "application/pdf" -> "pdf"
            "image/png" -> "png"; "image/jpeg" -> "jpg"; else -> return name
        }
        return "$name.$suffix"
    }
    private val escapedBytes = Regex("(?:(?:%[cCdD][0-9a-fA-F]%[89aAbB][0-9a-fA-F])|(?:%[eE][0-9a-fA-F](?:%[89aAbB][0-9a-fA-F]){2})|(?:%[fF][0-4](?:%[89aAbB][0-9a-fA-F]){3}))+")
    fun readable(name: String): String = escapedBytes.replace(name) { match ->
        val bytes = match.value.chunked(3).map { it.drop(1).toInt(16).toByte() }.toByteArray()
        if (bytes.none { it.toInt() < 0 }) match.value else runCatching {
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (value.any { it == '/' || it == '\\' || it.isISOControl() }) match.value else value
        }.getOrDefault(match.value)
    }
}
