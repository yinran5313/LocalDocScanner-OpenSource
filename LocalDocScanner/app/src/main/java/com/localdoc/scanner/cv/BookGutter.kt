package com.localdoc.scanner.cv

import android.graphics.Bitmap
import kotlin.math.max

/** Column projection adapted from Leptonica pix3.c/pixCountPixelsByColumn (BSD-2-Clause).
 * Copyright (C) 2001-2020 Leptonica. Full notice: assets/third_party/leptonica_BSD.txt.
 * Adaptation: grayscale median per column, central search, low-confidence fallback.
 * A suggested cut is always adjustable; this does not flatten a curved book page.
 */
object BookGutter {
    data class Suggestion(val ratio: Float, val confident: Boolean)
    fun estimate(bitmap: Bitmap): Suggestion {
        val small = scaleBitmap(bitmap, 640)
        return try { val (gray, width) = toGray(small); estimateLuma(gray, width, small.height) }
        finally { if (small !== bitmap) small.recycle() }
    }

    fun estimateLuma(gray: IntArray, width: Int, height: Int): Suggestion {
        require(width > 0 && height > 0 && gray.size == width * height)
        if (width < 30 || height < 30) return Suggestion(.5f, false)
        val start = height / 6; val end = height * 5 / 6
        val stride = max(1, (end - start) / 96)
        val profile = DoubleArray(width) { x ->
            val values = (start until end step stride).map { y -> gray[y * width + x].coerceIn(0, 255) }.sorted()
            values[values.size / 2].toDouble()
        }
        val radius = max(1, width / 150)
        val smooth = DoubleArray(width) { x ->
            ((x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(width - 1)).map { profile[it] }.average()
        }
        val low = width * 3 / 10; val high = width * 7 / 10
        val cut = (low..high).minBy { smooth[it] + kotlin.math.abs(it - width / 2).toDouble() / width * 5 }
        val reference = listOf(smooth[(cut - width / 8).coerceAtLeast(0)], smooth[(cut + width / 8).coerceAtMost(width - 1)]).min()
        val confident = reference - smooth[cut] >= 18
        return Suggestion(if (confident) cut.toFloat() / width else .5f, confident)
    }
}
