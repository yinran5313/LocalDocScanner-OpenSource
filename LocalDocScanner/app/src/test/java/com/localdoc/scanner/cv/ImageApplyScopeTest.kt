package com.localdoc.scanner.cv

import org.junit.Assert.*
import org.junit.Test

class ImageApplyScopeTest {
    @Test fun reorderingDoesNotChangeTheChosenSourceFiles() {
        assertEquals(setOf("b","c"),ImageApplyScope.resolve(listOf("c","a","b"),2,0,listOf("b","c","deleted")))
        assertEquals(setOf("a"),ImageApplyScope.resolve(listOf("c","a","b"),1,1,emptyList()))
        assertEquals(setOf("a","b"),ImageApplyScope.resolve(listOf("a","b"),0,0,emptyList()))
        assertTrue(ImageApplyScope.resolve(listOf("a"),2,0,emptyList()).isEmpty())
    }
}
