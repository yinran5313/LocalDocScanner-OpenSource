package com.localdoc.scanner.models

import kotlin.math.roundToInt

/** Pixel budgets include the final ARGB image; inference is always a fixed-size tile. */
object RestorationGeometry {
    const val CORE = 160
    const val MAX_OUTPUT_PIXELS = 16_000_000L
    fun inputSide(scale: Int): Int {
        require(scale == 2 || scale == 4) { "请选择2倍或4倍" }
        return 4000 / scale
    }
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSide > 0)
        val factor = minOf(1.0, maxSide.toDouble() / maxOf(width, height))
        return (width * factor).roundToInt().coerceAtLeast(1) to (height * factor).roundToInt().coerceAtLeast(1)
    }
    fun tileCount(width: Int, height: Int): Int {
        require(width in 1..4000 && height in 1..4000)
        return ((width + CORE - 1) / CORE) * ((height + CORE - 1) / CORE)
    }
    fun requireOutput(width: Int, height: Int, scale: Int) {
        inputSide(scale)
        require(width > 0 && height > 0 && width.toLong() * height * scale * scale <= MAX_OUTPUT_PIXELS) { "输出超过1600万像素，请缩小图片" }
    }
}
