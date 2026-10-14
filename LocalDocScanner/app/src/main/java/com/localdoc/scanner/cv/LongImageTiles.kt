package com.localdoc.scanner.cv

import android.graphics.*
import com.localdoc.scanner.util.ImageIo
import com.localdoc.scanner.util.AtomicFiles
import java.io.File

/** Fixed-size canvas; even a tall single page is cut into adjacent non-overlapping tiles. */
object LongImageTiles {
    fun create(files: List<File>, directory: File, width: Int = 720, tileHeight: Int = 6000): List<File> {
        require(files.isNotEmpty() && width in 240..1440 && tileHeight in 1000..8000)
        val results = mutableListOf<File>(); val tile = Bitmap.createBitmap(width, tileHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile); canvas.drawColor(Color.WHITE); var top = 0
        fun flush() {
            if (top == 0) return
            val cropped = Bitmap.createBitmap(tile, 0, 0, width, top)
            try { val target = File(directory, "长图${results.size + 1}.jpg")
                AtomicFiles.write(target) { check(ImageIo.saveJpeg(cropped, it, 94)) }; results += target
            } finally { if (cropped !== tile) cropped.recycle() }
            top = 0; canvas.drawColor(Color.WHITE)
        }
        try { files.forEach { file ->
            val source = ImageIo.loadFromFile(file, 2400) ?: error("无法读取${file.name}")
            val h = (source.height.toLong() * width / source.width).toInt().coerceAtLeast(1)
            try {
                var offset = 0
                while (offset < h) {
                    val take = minOf(tileHeight - top, h - offset)
                    canvas.save(); canvas.clipRect(0, top, width, top + take)
                    canvas.drawBitmap(source, null, RectF(0f, (top - offset).toFloat(), width.toFloat(), (top - offset + h).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)); canvas.restore()
                    offset += take; top += take; if (top == tileHeight) flush()
                }
            } finally { source.recycle() }
        }; flush() } finally { tile.recycle() }
        return results
    }
}
