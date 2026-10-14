package com.localdoc.scanner.models

import java.text.Normalizer

/** BERT BasicTokenizer + greedy WordPiece, matching bge-small-zh-v1.5's uncased vocabulary. */
class WordPiece(lines: List<String>) {
    private val vocab = lines.withIndex().associate { it.value to it.index.toLong() }
    fun encode(text: String, limit: Int = 512): LongArray {
        val words = mutableListOf<String>(); val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { words += word.toString(); word.clear() } }
        val clean = Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        clean.codePoints().toArray().forEach { cp ->
            val category = Character.getType(cp)
            when {
                category == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> flush()
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x3134F ||
                    cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
                    category in setOf(20, 21, 22, 23, 24, 29, 30) -> { flush(); words += String(Character.toChars(cp)) }
                category in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) || cp == 0 || cp == 0xFFFD -> Unit
                else -> word.appendCodePoint(cp)
            }
        }; flush()
        val ids = mutableListOf(vocab["[CLS]"] ?: 101L)
        for (token in words) {
            if (ids.size >= limit - 1) break
            var start = 0; val pieces = mutableListOf<Long>(); var unknown = token.length > 100
            while (start < token.length && !unknown) {
                var end = token.length; var found: Long? = null
                while (end > start) {
                    found = vocab[(if (start == 0) "" else "##") + token.substring(start, end)]
                    if (found != null) break
                    end--
                }
                if (found == null) unknown = true else { pieces += found; start = end }
            }
            ids.addAll(if (unknown) listOf(vocab["[UNK]"] ?: 100L) else pieces)
        }
        return (ids.take(limit - 1) + (vocab["[SEP]"] ?: 102L)).toLongArray()
    }
}
