package com.localdoc.scanner.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ImageOrientationInstrumentedTest {
    @Test fun fileDecoderHonorsAllEightExifOrientationsIncludingMirrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        try {
            val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until 60) for (x in 0 until 40) bitmap.setPixel(x, y, colors[(if (y < 30) 0 else 2) + if (x < 20) 0 else 1])
                assertTrue(ImageIo.saveJpeg(bitmap, file, 100))
            } finally { bitmap.recycle() }
            for (orientation in 1..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val oriented = ImageIo.loadFromFile(file, 200)!!
                try {
                    assertEquals(if (orientation < 5) 40 else 60, oriented.width)
                    assertEquals(if (orientation < 5) 60 else 40, oriented.height)
                    val points = listOf(5 to 5, oriented.width - 6 to 5, 5 to oriented.height - 6, oriented.width - 6 to oriented.height - 6)
                    points.forEachIndexed { index, point ->
                        val actual = oriented.getPixel(point.first, point.second); val wanted = colors[expected[orientation - 1][index]]
                        assertTrue(kotlin.math.abs(Color.red(actual) - Color.red(wanted)) < 25)
                        assertTrue(kotlin.math.abs(Color.green(actual) - Color.green(wanted)) < 25)
                        assertTrue(kotlin.math.abs(Color.blue(actual) - Color.blue(wanted)) < 25)
                    }
                } finally { oriented.recycle() }
            }
        } finally { file.delete() }
    }
}
