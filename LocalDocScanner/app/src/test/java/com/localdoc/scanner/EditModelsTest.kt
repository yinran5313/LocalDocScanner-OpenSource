package com.localdoc.scanner

import com.localdoc.scanner.edit.NormalizedPoint
import com.localdoc.scanner.edit.EditRecipe
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.edit.defaultCropCorners
import com.localdoc.scanner.edit.isValidCrop
import com.localdoc.scanner.edit.moveCornerSafely
import com.localdoc.scanner.edit.moveEdgeSafely
import com.localdoc.scanner.edit.rotateCropClockwise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditModelsTest {

    @Test
    fun fourRotationsReturnToSameCrop() {
        val original = listOf(
            NormalizedPoint(0.1f, 0.2f),
            NormalizedPoint(0.8f, 0.1f),
            NormalizedPoint(0.9f, 0.85f),
            NormalizedPoint(0.15f, 0.9f)
        )
        var rotated = original
        repeat(4) { rotated = rotateCropClockwise(rotated) }
        original.zip(rotated).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.0001f)
            assertEquals(expected.y, actual.y, 0.0001f)
        }
    }

    @Test
    fun cornerCannotCrossAndInvalidatePolygon() {
        val original = defaultCropCorners()
        val moved = moveCornerSafely(original, 0, NormalizedPoint(0.99f, 0.99f))
        assertEquals(original, moved)
        assertTrue(isValidCrop(moved))
    }

    @Test
    fun edgeMoveStaysInsideImage() {
        val moved = moveEdgeSafely(defaultCropCorners(), 0, 0f, -0.5f)
        assertTrue(moved.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(0f, moved[0].y, 0.0001f)
        assertEquals(0f, moved[1].y, 0.0001f)
        assertTrue(isValidCrop(moved))
    }

    @Test
    fun cropRecipeCornersRoundTrip() {
        val recipe = EditRecipe(
            quarterTurns = 3,
            corners = listOf(
                NormalizedPoint(0.08f, 0.11f),
                NormalizedPoint(0.91f, 0.07f),
                NormalizedPoint(0.88f, 0.93f),
                NormalizedPoint(0.12f, 0.89f)
            ),
            filter = ScanFilter.COLOR_BOOST,
            brightness = 0.12f,
            contrast = 1.2f
        )
        val decoded = EditRecipe.decodeCorners(recipe.encodeCorners())
        recipe.corners.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.0001f)
            assertEquals(expected.y, actual.y, 0.0001f)
        }
    }
}
