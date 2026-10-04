package com.localdoc.scanner.ocr
import org.junit.Assert.*
import org.junit.Test
class OcrLanguageTest {
    @Test fun japaneseCannotUseTinyAndLegacyDefaultsToMixed() { assertTrue(OcrLanguage.JAPANESE.medium(false)); assertFalse(OcrLanguage.ENGLISH.medium(false)); assertEquals(OcrLanguage.AUTO,OcrLanguage.fromCode(null)) }
}
