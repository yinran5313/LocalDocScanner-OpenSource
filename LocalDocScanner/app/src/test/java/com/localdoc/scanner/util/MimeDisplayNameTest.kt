package com.localdoc.scanner.util

import org.junit.Assert.*
import org.junit.Test

class MimeDisplayNameTest {
    @Test fun renamedLibraryFilesKeepTheirActualReaderAfterLosingSuffix() {
        assertEquals("讲义.md",DisplayNames.withMimeExtension("讲义","text/markdown"))
        assertEquals("资料.zip",DisplayNames.withMimeExtension("资料","application/zip"))
        assertEquals("复核.v1.csv",DisplayNames.withMimeExtension("复核.v1","text/csv"))
        assertEquals("笔记.markdown",DisplayNames.withMimeExtension("笔记.markdown","text/plain"))
        assertEquals("文件",DisplayNames.withMimeExtension("文件","application/octet-stream"))
    }
}
