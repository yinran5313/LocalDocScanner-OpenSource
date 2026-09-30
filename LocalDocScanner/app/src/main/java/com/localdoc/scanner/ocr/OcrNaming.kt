package com.localdoc.scanner.ocr

object OcrNaming {
    fun suggest(text: String, fallback: String = "扫描文档"): String {
        val candidate = text.lineSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .firstOrNull { !it.matches(Regex("【第\\s*\\d+\\s*页】")) }
            .orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '。', '，', ',', ':', '：', ';', '；')
        return candidate.take(28).ifBlank { fallback }
    }
}
