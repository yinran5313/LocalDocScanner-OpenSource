package com.localdoc.scanner

import org.junit.Assert.assertEquals
import org.junit.Test
import org.libreoffice.androidlib.OfficeFileName
import java.util.Locale

class OfficeFileNameTest {
    @Test fun missingOrBlankMetadataUsesNonEmptyFallback() {
        for (missing in listOf(null, "", "   ")) {
            assertEquals("工作副本.docx", OfficeFileName.displayName(missing, "工作副本.docx", true))
            assertEquals("工作副本", OfficeFileName.displayName(missing, "工作副本.docx", false))
            assertEquals("文档", OfficeFileName.displayName(missing, null, false))
        }
    }

    @Test fun namesWithoutExtensionsAndLeadingDotsRemainUsable() {
        for (name in listOf("会议记录", ".hidden", "文档")) {
            assertEquals(name, OfficeFileName.displayName(name, null, false))
            assertEquals("", OfficeFileName.extension(name))
        }
        assertEquals("", OfficeFileName.extension(null))
        assertEquals("", OfficeFileName.extension("报告."))
        assertEquals("报告", OfficeFileName.displayName("报告.", null, false))
    }

    @Test fun onlyTheLastExtensionIsRemovedAndOriginalNameIsRetained() {
        assertEquals("2026.会议记录", OfficeFileName.displayName("2026.会议记录.DOCX", null, false))
        assertEquals("2026.会议记录.DOCX", OfficeFileName.displayName("2026.会议记录.DOCX", null, true))
        assertEquals("docx", OfficeFileName.extension("2026.会议记录.DOCX"))
    }

    @Test fun extensionNormalizationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("tiff", OfficeFileName.extension("扫描.TIFF"))
        } finally { Locale.setDefault(previous) }
    }
}
