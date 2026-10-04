package com.localdoc.scanner.pdf
import org.junit.Assert.*
import org.junit.Test
class PdfDecorationOptionsTest {
    @Test fun numbersUseRequestedStartPrefixSuffixAndTotal() {
        assertEquals("第8页 / 共12页", PdfDecorationOptions(5, "第", "页 / 共{total}页", 4).numberLabel(3, 12))
        assertEquals("2 / 10", PdfDecorationOptions().numberLabel(1, 10))
    }
    @Test fun invalidOptionsFailRatherThanSilentlyReset() {
        assertThrows(IllegalArgumentException::class.java) { PdfDecorationOptions(numberStart = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { PdfDecorationOptions(numberPosition = 6).validate() }
        assertThrows(IllegalArgumentException::class.java) { PdfDecorationOptions(watermarkAngle = Float.NaN).validate() }
    }
}
