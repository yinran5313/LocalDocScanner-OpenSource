package com.localdoc.scanner.edit
import org.junit.Assert.*
import org.junit.Test
class CropAspectTest {
    @Test fun ratioIsInPixelCoordinatesAndInsideImage() {
        val points=CropAspect.fit(defaultCropCorners(0f),85.6f/54f,3f/4f)
        assertEquals(85.6f/54f,(points[1].x-points[0].x)*0.75f/(points[3].y-points[0].y),0.0001f)
        assertTrue(points.all { it.x in 0f..1f && it.y in 0f..1f }); assertTrue(isValidCrop(points))
    }
    @Test fun freeSelectionAndInvalidRatioRemainUntouched() {
        val points=defaultCropCorners(); assertEquals(points,CropAspect.fit(points,0f,1f)); assertEquals(points,CropAspect.fit(points,Float.NaN,1f))
    }
}
