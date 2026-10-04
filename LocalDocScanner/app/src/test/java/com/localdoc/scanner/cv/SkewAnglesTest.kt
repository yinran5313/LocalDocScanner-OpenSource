package com.localdoc.scanner.cv

import org.junit.Assert.*
import org.junit.Test

class SkewAnglesTest {
    @Test fun coherentTextLinesOutweighSingleLongPageBorder() {
        val angle = SkewAngles.consensus(listOf(3.0 to 90.0, 3.1 to 100.0, 2.9 to 85.0, 3.2 to 100.0, -9.0 to 3000.0))
        assertNotNull(angle); assertEquals(3.05, angle!!, .1)
    }
    @Test fun sparseOrConflictingLinesCannotForceRotation() {
        assertNull(SkewAngles.consensus(List(3) { 4.0 to 100.0 }))
        assertNull(SkewAngles.consensus(listOf(-8.0, -7.0, -6.0, 5.0, 6.0, 7.0).map { it to 100.0 }))
        assertNull(SkewAngles.consensus(List(10) { Double.NaN to 100.0 }))
    }
}
