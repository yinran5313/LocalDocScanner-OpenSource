package com.localdoc.scanner.util
import org.junit.Assert.assertEquals
import org.junit.Test
class DisplayNamesTest {
    @Test fun encodedChineseBecomesReadableWithoutChangingPlusOrAsciiEscapes() {
        assertEquals("工作+资料%20原件.docx", DisplayNames.readable("%E5%B7%A5%E4%BD%9C+%E8%B5%84%E6%96%99%20原件.docx"))
    }
    @Test fun malformedAndLiteralPercentRemainUnchanged() {
        listOf("100%预算.docx", "file%2Fpart.docx", "%E9%AA", "%FF").forEach {
            assertEquals(it, DisplayNames.readable(it))
        }
        assertEquals("工%2F私密", DisplayNames.readable("%E5%B7%A5%2F私密"))
    }
    @Test fun normalNamesAreUntouched() {
        assertEquals("拾页+原件.pdf", DisplayNames.readable("拾页+原件.pdf"))
    }
}
