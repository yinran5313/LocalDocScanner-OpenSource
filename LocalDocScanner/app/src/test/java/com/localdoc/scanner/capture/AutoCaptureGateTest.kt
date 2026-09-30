package com.localdoc.scanner.capture

import com.localdoc.scanner.cv.Point
import com.localdoc.scanner.cv.Quad
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureGateTest {
    private val page = Quad(Point(.1f, .1f), Point(.9f, .1f), Point(.9f, .9f), Point(.1f, .9f))
    @Test fun stationaryPageIsCapturedOnceRegardlessOfFrameRate() {
        for (interval in listOf(50L, 200L)) {
            val gate = AutoCaptureGate()
            val shots = (0L..10_000L step interval).count { gate.observe(page, 0L, it, true) }
            assertEquals(1, shots)
        }
    }
    @Test fun removalAndReplacementAllowsNextPage() {
        val gate = AutoCaptureGate()
        for (time in 0L..1000L step 100L) gate.observe(page, 0L, time, true)
        gate.observe(null, null, 1800L, true)
        gate.observe(null, null, 2300L, true)
        assertFalse(gate.observe(page, 0L, 2700L, true))
        assertTrue(gate.observe(page, 0L, 3700L, true))
    }
    @Test fun pageFlipWithSameOutlineRearmsAfterContentChange() {
        val gate = AutoCaptureGate()
        gate.observe(page, 0L, 0, true)
        assertTrue(gate.observe(page, 0L, 1000, true))
        val events = (2000L..5000L step 100L).count { gate.observe(page, 0xffffL, it, true) }
        assertEquals(1, events)
    }
    @Test fun badQualityAndShortDetectionLossDoNotTriggerAnotherShot() {
        val gate = AutoCaptureGate()
        for (time in 0L..2000L step 100L) assertFalse(gate.observe(page, 0L, time, false))
        gate.observe(page, 0L, 2100, true)
        assertTrue(gate.observe(page, 0L, 3100, true))
        gate.observe(null, null, 5000, true)
        assertFalse(gate.observe(page, 0L, 5200, true))
        assertFalse(gate.observe(page, 0L, 7000, true))
    }
}
