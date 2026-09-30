package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android/JNI acceptance fixtures; compiling these does not mean they were run. */
@RunWith(AndroidJUnit4::class)
class OpenCvDocumentInstrumentedTest {
    @Test fun detectsPaperAndWarpPreservesColorAndDimensions() {
        assertTrue(OpenCvDocument.available())
        val image = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(30, 40, 50))
            drawRect(40f, 50f, 360f, 450f, Paint().apply { color = Color.WHITE })
            drawRect(120f, 180f, 170f, 230f, Paint().apply { color = Color.RED })
        }
        val quad = OpenCvDocument.detect(image)!!
        assertEquals(40f, quad.p0.x, 5f)
        assertEquals(50f, quad.p0.y, 5f)
        val output = OpenCvDocument.warp(image, quad, 320, 400)!!
        assertEquals(320, output.width); assertEquals(400, output.height)
        val stamp = output.getPixel(100, 150)
        assertTrue(Color.red(stamp) > 180 && Color.green(stamp) < 80)
        image.recycle(); output.recycle()
    }
    @Test fun enhancementPreservesRedStampAndDoesNotMutateOriginal() {
        val image = Bitmap.createBitmap(320, 400, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(170, 170, 170))
            drawRect(50f, 100f, 100f, 150f, Paint().apply { color = Color.rgb(160, 20, 20) })
        }
        val result = OpenCvDocument.flattenIllumination(image)!!
        val stamp = result.getPixel(70, 120)
        assertTrue(Color.red(stamp) > Color.green(stamp) + 70)
        assertEquals(Color.rgb(160, 20, 20), image.getPixel(70, 120))
        image.recycle(); result.recycle()
    }
}
