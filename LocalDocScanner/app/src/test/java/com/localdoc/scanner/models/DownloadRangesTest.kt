package com.localdoc.scanner.models

import org.junit.Assert.*
import org.junit.Test

class DownloadRangesTest {
    @Test fun handlesBothPartialStatusAndLegacyPartial200() {
        assertTrue(DownloadRanges.retainsPrefix(206,64,2035,"bytes 64-2034/2035"))
        assertTrue(DownloadRanges.retainsPrefix(200,64,2035,"bytes 64-2034/2035"))
        assertTrue(DownloadRanges.retainsPrefix(200,64,2035,""))
        assertTrue(DownloadRanges.retainsPrefix(200,64,2035,""))
    }
    @Test fun neverAppendsAChangedOrMisalignedPartialFile() {
        assertTrue(runCatching { DownloadRanges.retainsPrefix(206,64,2035,"bytes 0-2034/2035") }.isFailure)
        assertTrue(runCatching { DownloadRanges.retainsPrefix(206,64,2035,"bytes 64-2099/2100") }.isFailure)
        assertTrue(runCatching { DownloadRanges.retainsPrefix(206,64,2035,"") }.isFailure)
    }
}
