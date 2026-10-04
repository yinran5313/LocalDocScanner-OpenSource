package com.localdoc.scanner.cv

import org.junit.Assert.*
import org.junit.Test

class BitmapBudgetTest {
    @Test fun manyTallPagesStayWithinPixelAndDimensionBudget() {
        val plan = BitmapBudget.stitchPlan(List(150) { 2400 to 4000 }, 1080)
        assertTrue(plan.width.toLong() * plan.totalHeight <= BitmapBudget.MAX_PIXELS)
        assertTrue(plan.totalHeight <= BitmapBudget.MAX_SIDE)
        assertEquals(150, plan.heights.size)
        assertTrue(plan.heights.all { it > 0 })
    }
    @Test fun ordinaryPagesKeepRequestedWidth() {
        val plan = BitmapBudget.stitchPlan(listOf(2000 to 3000, 1000 to 1200), 720)
        assertEquals(720, plan.width)
        assertEquals(listOf(1080, 864), plan.heights)
    }
    @Test fun invalidOrExcessiveInputIsRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { BitmapBudget.stitchPlan(listOf(0 to 100), 1080) }
        assertThrows(IllegalArgumentException::class.java) { BitmapBudget.stitchPlan(List(31000) { 1 to 1 }, 1080) }
    }
}
