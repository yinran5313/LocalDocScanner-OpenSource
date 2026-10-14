package com.localdoc.scanner.util

/** Leaves room for AtomicFiles' temporary suffix on Android's 255-byte filesystems. */
object FileNames {
    fun safe(name: String, maxBytes: Int = 180): String {
        require(maxBytes >= 32)
        val normalized=name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().takeUnless { it.isBlank() || it in setOf(".","..") } ?: "文档"
        val extension=normalized.substringAfterLast('.',"").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) && normalized.contains('.') }?.let { ".$it" }.orEmpty()
        val stem=if(extension.isNotEmpty()) normalized.dropLast(extension.length) else normalized
        val budget=maxBytes-extension.toByteArray(Charsets.UTF_8).size
        val result=StringBuilder(); var at=0; var bytes=0
        while(at<stem.length) {
            val code=stem.codePointAt(at); val piece=String(Character.toChars(code)); val length=piece.toByteArray(Charsets.UTF_8).size
            if(bytes+length>budget) break
            result.append(piece); bytes+=length; at+=Character.charCount(code)
        }
        return result.toString().trim().ifBlank { "文档" }+extension
    }
}
