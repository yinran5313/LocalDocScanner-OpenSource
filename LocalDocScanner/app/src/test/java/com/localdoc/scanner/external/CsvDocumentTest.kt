package com.localdoc.scanner.external

import org.junit.Assert.*
import org.junit.Test

class CsvDocumentTest {
    @Test fun quotesLineBreaksEmptyFieldsBomAndTextNumbersSurviveEdits() {
        val input = "\uFEFF编号,备注,公式,空值\r\n0012,\"第一行\n第二行,带逗号\",=A1,\r\n0099,\"他说\"\"好\"\"\",,尾\r\n"
        val parsed = CsvDocument.parse(input)
        assertEquals("0012", parsed.rows[1][0])
        assertEquals("第一行\n第二行,带逗号", parsed.rows[1][1])
        val rows = parsed.rows.mapIndexed { i, row -> if (i == 2) row.toMutableList().apply { set(3, "新,内容") } else row }
        val serialized = parsed.encode(rows)
        assertTrue(serialized.startsWith("\uFEFF")); assertTrue(serialized.contains("\r\n"))
        assertEquals(rows, CsvDocument.parse(serialized).rows)
        assertEquals("=A1", CsvDocument.parse(serialized).rows[1][2])
    }
    @Test fun tabSeparatedFilesPreserveTabsInsideQuotedCells() {
        val parsed = CsvDocument.parse("a\tb\n\"x\ty\"\t0001\n", '\t')
        assertEquals(listOf("x\ty", "0001"), parsed.rows[1])
        assertEquals(parsed.rows, CsvDocument.parse(parsed.encode(parsed.rows), '\t').rows)
    }
    @Test fun oversizeAndMalformedTablesFailWithoutTruncation() {
        assertThrows(IllegalArgumentException::class.java) { CsvDocument.parse("x".repeat(2_000_001)) }
        assertThrows(IllegalArgumentException::class.java) { CsvDocument.parse(List(201) { "x" }.joinToString(",")) }
        assertThrows(Exception::class.java) { CsvDocument.parse("\"not closed") }
    }
}
