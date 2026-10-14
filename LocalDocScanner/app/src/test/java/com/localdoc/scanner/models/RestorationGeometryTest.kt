package com.localdoc.scanner.models

import org.junit.Assert.*
import org.junit.Test

class RestorationGeometryTest {
    @Test fun largePhotosRetainNearTargetDetailAndAspect() {
        assertEquals(2000 to 1500,RestorationGeometry.fit(4032,3024,2000))
        assertEquals(1500 to 2000,RestorationGeometry.fit(3024,4032,2000))
        assertEquals(96 to 128,RestorationGeometry.fit(96,128,2000))
        assertEquals(1 to 2000,RestorationGeometry.fit(1,8000,2000))
    }
    @Test fun bothScaleModesRespectFinalPixelBudget() {
        for(scale in listOf(2,4)) {
            val side=RestorationGeometry.inputSide(scale)
            RestorationGeometry.requireOutput(side,side,scale)
            assertEquals(4000,side*scale)
            assertTrue(runCatching { RestorationGeometry.requireOutput(side+1,side+1,scale) }.isFailure)
        }
        assertTrue(runCatching { RestorationGeometry.inputSide(3) }.isFailure)
    }
    @Test fun partialEdgeTilesAreIncluded() {
        assertEquals(1,RestorationGeometry.tileCount(160,160))
        assertEquals(6,RestorationGeometry.tileCount(321,161))
        assertTrue(runCatching { RestorationGeometry.tileCount(0,160) }.isFailure)
    }
}
