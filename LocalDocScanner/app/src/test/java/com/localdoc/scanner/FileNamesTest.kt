package com.localdoc.scanner

import com.localdoc.scanner.util.FileNames
import org.junit.Assert.*
import org.junit.Test

class FileNamesTest {
    @Test fun longUnicodeNamesKeepFormatAndFitAtomicTemporaryFiles() {
        for(name in listOf("长中文文件名".repeat(40)+".docx","📄书页".repeat(100)+".PDF","x".repeat(300)+".xlsx")) {
            val safe=FileNames.safe(name)
            assertTrue(safe.toByteArray(Charsets.UTF_8).size<=180); assertEquals(name.substringAfterLast('.'),safe.substringAfterLast('.'))
            assertFalse(safe.dropLast(safe.substringAfterLast('.').length+1).last().isHighSurrogate())
        }
    }
    @Test fun unsafeOrEmptyNamesNeverBecomeRelativePaths() {
        for(name in listOf(".",".."," ","a/b\\c:?.txt","bad\u0000.txt")) { val safe=FileNames.safe(name); assertFalse(safe.contains('/')); assertFalse(safe.contains('\\')); assertFalse(safe.contains('\u0000')); assertFalse(safe in listOf(".","..","")) }
    }
}
