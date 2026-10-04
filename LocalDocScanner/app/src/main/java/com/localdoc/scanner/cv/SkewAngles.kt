package com.localdoc.scanner.cv

import kotlin.math.abs

/** Consensus of nearly horizontal lines. Long page edges alone cannot establish a text angle. */
internal object SkewAngles {
    fun consensus(lines: List<Pair<Double, Double>>): Double? {
        val valid = lines.filter { (angle, length) -> angle.isFinite() && length.isFinite() && abs(angle) <= 12 && length > 0 }
        if (valid.size < 4) return null
        val sorted = valid.sortedBy { it.first }
        val middle = sorted[sorted.size / 2].first
        val supported = valid.filter { abs(it.first - middle) <= 1.2 }
        if (supported.size < 4 || supported.size < valid.size * .6) return null
        val medianLength = supported.map { it.second }.sorted()[supported.size / 2]
        val totalWeight = supported.sumOf { minOf(it.second, medianLength * 2) }
        return supported.sumOf { it.first * minOf(it.second, medianLength * 2) } / totalWeight
    }
}
