package com.localdoc.scanner.ocr

/** PP-OCRv6 has one multilingual vocabulary; Japanese requires medium rather than tiny. */
enum class OcrLanguage(val label: String, val requiresMedium: Boolean = false) {
    AUTO("多语言混合"), CHINESE("简体中文"), TRADITIONAL("繁体中文"), ENGLISH("英文"), JAPANESE("日文", true), LATIN("拉丁字母语言");
    companion object { fun fromCode(code: String?) = entries.firstOrNull { it.name == code } ?: AUTO }
    fun medium(precise: Boolean) = precise || requiresMedium
}
