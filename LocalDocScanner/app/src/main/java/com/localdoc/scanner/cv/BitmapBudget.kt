package com.localdoc.scanner.cv

import kotlin.math.sqrt

data class StitchPlan(val width: Int, val heights: List<Int>) {
    val totalHeight: Int get() = heights.sum()
}

object BitmapBudget {
    const val MAX_PIXELS = 12_000_000L
    const val MAX_SIDE = 30_000
    fun stitchPlan(dimensions: List<Pair<Int, Int>>, requestedWidth: Int): StitchPlan {
        require(dimensions.isNotEmpty() && dimensions.all { it.first > 0 && it.second > 0 }) { "图片尺寸不可用" }
        val requested = requestedWidth.coerceIn(64, 2000)
        val ratios = dimensions.map { it.second.toDouble() / it.first }
        val totalAtWidth = ratios.sum() * requested
        val scale = minOf(1.0, sqrt(MAX_PIXELS / (requested * totalAtWidth)), MAX_SIDE / totalAtWidth)
        var width = (requested * scale).toInt().coerceAtLeast(1)
        fun heights(w: Int) = ratios.map { (it * w).toInt().coerceAtLeast(1) }
        var values = heights(width)
        while (width > 1 && (values.sumOf(Int::toLong) > MAX_SIDE || width * values.sumOf(Int::toLong) > MAX_PIXELS)) {
            width--; values = heights(width)
        }
        require(values.sumOf(Int::toLong) <= MAX_SIDE && width * values.sumOf(Int::toLong) <= MAX_PIXELS) { "图片数量超过长图预算，请分组拼接" }
        return StitchPlan(width, values)
    }
}
