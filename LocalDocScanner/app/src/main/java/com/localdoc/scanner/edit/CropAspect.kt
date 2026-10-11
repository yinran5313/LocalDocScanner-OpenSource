package com.localdoc.scanner.edit

/** Inscribe a rectangle in the current selection in image coordinates, without leaving the image. */
object CropAspect {
    val presets = listOf("自由" to 0f, "A4 竖版" to (210f/297f), "A4 横版" to (297f/210f), "正方形" to 1f, "证件/银行卡" to (85.6f/54f), "4:3" to (4f/3f))
    fun fit(points: List<NormalizedPoint>, ratio: Float, imageAspect: Float): List<NormalizedPoint> {
        if (!ratio.isFinite() || ratio <= 0f || imageAspect <= 0f || !imageAspect.isFinite() || points.size != 4) return points
        val left = points.minOf { it.x }.coerceIn(0f,1f); val right = points.maxOf { it.x }.coerceIn(0f,1f)
        val top = points.minOf { it.y }.coerceIn(0f,1f); val bottom = points.maxOf { it.y }.coerceIn(0f,1f)
        val normalizedRatio = ratio / imageAspect
        val width = minOf(right-left, (bottom-top)*normalizedRatio)
        val height = width/normalizedRatio
        val x=(left+right-width)/2f; val y=(top+bottom-height)/2f
        val next=listOf(NormalizedPoint(x,y),NormalizedPoint(x+width,y),NormalizedPoint(x+width,y+height),NormalizedPoint(x,y+height))
        return if (isValidCrop(next)) next else points
    }
}
