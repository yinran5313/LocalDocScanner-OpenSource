package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/** 长图拼接：多页纵向拼一张 */
object Stitch {

    fun vertical(images: List<Bitmap>, maxWidth: Int = 1080): Bitmap? {
        if (images.isEmpty()) return null
        val scaled = images.map { b ->
            val s = (maxWidth.toFloat() / b.width).coerceAtMost(1f)
            if (s < 1f) {
                Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
            } else {
                b
            }
        }
        val width = scaled.maxOf { it.width }
        val total = scaled.sumOf { it.height }
        if (width <= 0 || total <= 0) return null
        val out = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        var y = 0
        scaled.forEach { bmp ->
            canvas.drawBitmap(bmp, ((width - bmp.width) / 2f), y.toFloat(), null)
            y += bmp.height
        }
        return out
    }
}
