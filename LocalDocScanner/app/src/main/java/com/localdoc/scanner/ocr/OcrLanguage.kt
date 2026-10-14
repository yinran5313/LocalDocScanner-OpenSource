package com.localdoc.scanner.ocr

/** PP-OCRv6 has one multilingual vocabulary; all selections use the bundled medium model. */
enum class OcrLanguage(val label: String) {
    AUTO("多语言混合"), CHINESE("简体中文"), TRADITIONAL("繁体中文"), ENGLISH("英文"), JAPANESE("日文"), LATIN("拉丁字母语言");
    companion object { fun fromCode(code: String?) = entries.firstOrNull { it.name == code } ?: AUTO }
    // Keep old persisted task flags compatible with the single bundled model.
    fun medium(@Suppress("UNUSED_PARAMETER") precise: Boolean) = true
}
