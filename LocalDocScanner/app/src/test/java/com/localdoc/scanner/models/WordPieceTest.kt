package com.localdoc.scanner.models

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Test
import org.junit.Assert.*

class WordPieceTest {
    data class Fixture(val text: String, val ids: List<Long>)
    @Test fun matchesIndependentRustTokenizerForChineseLatinAccentsAndSupplementaryCharacters() {
        val loader = javaClass.classLoader!!
        val tokenizer = WordPiece(loader.getResourceAsStream("model-fixtures/vocab.txt")!!.bufferedReader().readLines())
        val cases: List<Fixture> = Gson().fromJson(loader.getResourceAsStream("model-fixtures/wordpiece.json")!!.bufferedReader(), object: TypeToken<List<Fixture>>() {}.type)
        cases.forEach { assertArrayEquals(it.text, it.ids.toLongArray(), tokenizer.encode(it.text)) }
    }
    @Test fun truncationRetainsBothSpecialTokensAndNeverExceedsBudget() {
        val loader = javaClass.classLoader!!
        val tokenizer = WordPiece(loader.getResourceAsStream("model-fixtures/vocab.txt")!!.bufferedReader().readLines())
        val ids = tokenizer.encode("报销材料".repeat(1000), 32)
        assertEquals(32, ids.size); assertEquals(101L, ids.first()); assertEquals(102L, ids.last())
    }
}
