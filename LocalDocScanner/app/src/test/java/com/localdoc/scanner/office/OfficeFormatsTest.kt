package com.localdoc.scanner.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeFormatsTest {
    @Test
    fun detectsModernLegacyAndOpenDocumentFormats() {
        assertEquals(OfficeFamily.WORD, OfficeFormats.detect("公文.DOC")?.family)
        assertEquals(OfficeFamily.SHEET, OfficeFormats.detect("清单.xlsx")?.family)
        assertEquals(OfficeFamily.SLIDES, OfficeFormats.detect("汇报.odp")?.family)
        assertTrue(OfficeFormats.detect("编辑.docx")?.quickEditSupported == true)
    }

    @Test
    fun fallsBackToMimeWhenProviderNameHasNoExtension() {
        val format = OfficeFormats.detect("外部文件", "application/vnd.ms-excel")
        assertEquals("xls", format?.extension)
        assertEquals("外部文件.xls", OfficeFormats.ensureExtension("外部文件", format))
        assertEquals("下载文件.bin.xls", OfficeFormats.ensureExtension("下载文件.bin", format))
    }

    @Test
    fun rejectsUnrelatedFormats() {
        assertNull(OfficeFormats.detect("照片.jpg", "image/jpeg"))
        assertNull(OfficeFormats.detect("合同.pdf", "application/pdf"))
    }
}
