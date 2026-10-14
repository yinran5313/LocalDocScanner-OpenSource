package com.localdoc.scanner.pdf

import org.junit.Test
import org.junit.Assert.*

class PageSelectionTest {
    @Test fun preservesExplicitOrderAndDeduplicatesOverlaps() {
        assertEquals(listOf(4, 0, 1, 2), PageSelection.parse("5,1-3,2", 5))
        assertEquals(listOf(0,1,2), PageSelection.parse("", 3))
        assertEquals(listOf(0,2), PageSelection.parse("1，3", 3))
    }
    @Test fun invalidRangesNeverSilentlyExportDifferentPages() {
        listOf("0", "6", "3-1", "1,,3", "1-3x", "999999999999999999").forEach { raw ->
            assertTrue(raw, runCatching { PageSelection.parse(raw, 5) }.isFailure)
        }
    }
}
