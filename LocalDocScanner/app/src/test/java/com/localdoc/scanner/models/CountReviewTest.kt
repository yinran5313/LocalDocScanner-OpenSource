package com.localdoc.scanner.models

import org.junit.Assert.*
import org.junit.Test

class CountReviewTest {
    @Test fun addingMissingMarkDeletingItAndDuplicateTapPreserveCountAndOrder() {
        val initial=(0 until 24).map {CountMark(.1f+(it%6)*.1f,.1f+(it/6)*.1f)}
        val added=CountReview.changed(initial,.9f,.9f,false,.04f)
        assertEquals(25,added.size);assertTrue(added.last().manual)
        assertEquals(added,CountReview.changed(added,.91f,.9f,false,.04f))
        assertEquals(initial,CountReview.changed(added,.9f,.9f,true,.04f))
        assertEquals(initial,CountReview.changed(initial,.9f,.9f,true,.04f))
    }
    @Test fun deleteUsesImageAspectRatioAndRejectsNonFiniteCoordinates() {
        val points=listOf(CountMark(.5f,.5f))
        assertEquals(1,CountReview.changed(points,.5f,.55f,true,.04f,.5f).size)
        assertThrows(IllegalArgumentException::class.java) {CountReview.changed(points,Float.NaN,.5f,false,.04f)}
    }
}
