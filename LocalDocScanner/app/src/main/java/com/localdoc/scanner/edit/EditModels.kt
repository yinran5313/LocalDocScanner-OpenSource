package com.localdoc.scanner.edit

import com.localdoc.scanner.cv.Point
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.isConvex
import com.localdoc.scanner.cv.polygonArea
import java.io.File

data class NormalizedPoint(val x: Float, val y: Float) {
    fun clamped(): NormalizedPoint = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class EditRecipe(
    val quarterTurns: Int = 0,
    val corners: List<NormalizedPoint> = defaultCropCorners(),
    val filter: ScanFilter = ScanFilter.AUTO,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val fineRotation: Float = 0f
) {
    fun encodeCorners(): String = corners.joinToString(";") { "${it.x},${it.y}" }

    companion object {
        fun decodeCorners(value: String): List<NormalizedPoint> {
            val parsed = value.split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                if (parts.size != 2) return@mapNotNull null
                val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
                val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
                NormalizedPoint(x, y).clamped()
            }
            return parsed.takeIf { it.size == 4 && isValidCrop(it) } ?: defaultCropCorners()
        }
    }
}

data class EditResult(
    val sourceFile: File,
    val renderedFile: File,
    val recipe: EditRecipe,
    val width: Int,
    val height: Int
)

fun defaultCropCorners(margin: Float = 0.035f): List<NormalizedPoint> = listOf(
    NormalizedPoint(margin, margin),
    NormalizedPoint(1f - margin, margin),
    NormalizedPoint(1f - margin, 1f - margin),
    NormalizedPoint(margin, 1f - margin)
)

fun rotateCropClockwise(points: List<NormalizedPoint>): List<NormalizedPoint> {
    if (points.size != 4) return defaultCropCorners()
    val transformed = points.map { NormalizedPoint(1f - it.y, it.x) }
    return listOf(transformed[3], transformed[0], transformed[1], transformed[2])
}

fun moveCornerSafely(points: List<NormalizedPoint>, index: Int, target: NormalizedPoint): List<NormalizedPoint> {
    if (points.size != 4 || index !in 0..3) return points
    val candidate = points.toMutableList().apply { this[index] = target.clamped() }
    return if (isValidCrop(candidate)) candidate else points
}

fun moveEdgeSafely(points: List<NormalizedPoint>, edgeIndex: Int, dx: Float, dy: Float): List<NormalizedPoint> {
    if (points.size != 4 || edgeIndex !in 0..3) return points
    val first = edgeIndex
    val second = (edgeIndex + 1) % 4
    val appliedDx = if (dx >= 0f) {
        minOf(dx, 1f - points[first].x, 1f - points[second].x)
    } else {
        maxOf(dx, -points[first].x, -points[second].x)
    }
    val appliedDy = if (dy >= 0f) {
        minOf(dy, 1f - points[first].y, 1f - points[second].y)
    } else {
        maxOf(dy, -points[first].y, -points[second].y)
    }
    val candidate = points.toMutableList()
    candidate[first] = NormalizedPoint(points[first].x + appliedDx, points[first].y + appliedDy)
    candidate[second] = NormalizedPoint(points[second].x + appliedDx, points[second].y + appliedDy)
    return if (isValidCrop(candidate)) candidate else points
}

fun isValidCrop(points: List<NormalizedPoint>): Boolean {
    if (points.size != 4) return false
    val cv = points.map { Point(it.x, it.y) }
    return isConvex(cv) && polygonArea(cv) >= 0.015f
}
