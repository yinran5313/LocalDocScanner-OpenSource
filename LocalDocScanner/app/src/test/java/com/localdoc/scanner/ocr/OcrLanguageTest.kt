package com.localdoc.scanner.ocr
import org.junit.Assert.*
import org.junit.Test
class OcrLanguageTest {
    @Test fun legacyFlagsAndUnknownLanguageUseBundledMedium() {
        OcrLanguage.entries.forEach { language ->
            assertTrue(language.medium(false))
            assertTrue(language.medium(true))
        }
        assertEquals(OcrLanguage.AUTO, OcrLanguage.fromCode(null))
        assertEquals(OcrLanguage.AUTO, OcrLanguage.fromCode("OLD_LANGUAGE"))
    }
}
