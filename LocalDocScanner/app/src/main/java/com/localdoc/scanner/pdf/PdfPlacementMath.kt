package com.localdoc.scanner.pdf

data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** PDF编辑画布与写入后端共享的0..1页面坐标约束。 */
object PdfPlacementMath {
    fun centeredAt(centerX: Float, centerY: Float, width: Float, height: Float): NormalizedRect {
        val w = width.coerceIn(0.01f, 1f)
        val h = height.coerceIn(0.01f, 1f)
        return NormalizedRect(
            (centerX - w / 2f).coerceIn(0f, 1f - w),
            (centerY - h / 2f).coerceIn(0f, 1f - h),
            w,
            h
        )
    }

    fun move(rect: NormalizedRect, dx: Float, dy: Float): NormalizedRect = rect.copy(
        x = (rect.x + dx).coerceIn(0f, 1f - rect.width),
        y = (rect.y + dy).coerceIn(0f, 1f - rect.height)
    )

    fun resize(rect: NormalizedRect, dw: Float, dh: Float): NormalizedRect = rect.copy(
        width = (rect.width + dw).coerceIn(0.04f, 1f - rect.x),
        height = (rect.height + dh).coerceIn(0.025f, 1f - rect.y)
    )
}
