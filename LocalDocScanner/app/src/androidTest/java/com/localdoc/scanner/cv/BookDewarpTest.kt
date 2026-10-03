package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDewarpTest {
    @Test fun blankPageCannotBecomeAFalselySuccessfulDewarp() {
        val page = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val result = BookDewarp.flatten(page)
            try {
                assertFalse(result.applied)
                assertTrue(page.sameAs(result.bitmap))
                assertTrue(result.note.contains("保留原页"))
            } finally { result.bitmap.recycle() }
        } finally { page.recycle() }
    }
}
