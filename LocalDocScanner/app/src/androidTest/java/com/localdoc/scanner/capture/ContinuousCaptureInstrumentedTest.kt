package com.localdoc.scanner.capture

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localdoc.scanner.data.DraftStore
import com.localdoc.scanner.data.ScanDraft
import com.localdoc.scanner.util.ImageIo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContinuousCaptureInstrumentedTest {
    @Test fun bookShotCommitsTwoOrderedPagesAndKeepsEditableSource() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "capture-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        try {
            val image = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, 500f, 600f, Paint().apply { color = Color.RED })
                drawRect(500f, 0f, 1000f, 600f, Paint().apply { color = Color.BLUE })
            }
            val shot = File(directory, "shot.jpg")
            assertTrue(ImageIo.saveJpeg(image, shot)); image.recycle()
            ContinuousCaptureProcessor.prepare(context, shot, book = true).use { prepared ->
                assertEquals(2, prepared.pages.size)
                val saved = DraftStore.addBatch(context, ScanDraft(), prepared.pages)
                assertEquals(2, saved.pages.size)
                assertEquals(saved.pages.map { it.id }, DraftStore.load(context).pages.map { it.id })
                val left = ImageIo.loadFromFile(File(saved.pages[0].sourcePath))!!
                val right = ImageIo.loadFromFile(File(saved.pages[1].sourcePath))!!
                try {
                    assertTrue(Color.red(left.getPixel(10, 10)) > 220)
                    assertTrue(Color.blue(right.getPixel(right.width - 10, 10)) > 220)
                    assertEquals(0f, saved.pages[0].recipe.corners.first().x, .001f)
                } finally { left.recycle(); right.recycle() }
            }
            assertTrue(shot.isFile)
        } finally { directory.deleteRecursively() }
    }
}
