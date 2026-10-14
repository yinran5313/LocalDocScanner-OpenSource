package com.localdoc.scanner.cv

import kotlin.math.roundToInt

object PortraitSizes {
    data class Size(val label: String, val width: Int, val height: Int, val widthMm: Float, val heightMm: Float)
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)
    val sizes = listOf(Size("保留原尺寸", 0, 0, 0f, 0f), Size("一寸 25×35mm", 295, 413, 25f, 35f),
        Size("二寸 35×49mm", 413, 579, 35f, 49f), Size("小二寸 33×48mm", 390, 567, 33f, 48f))
    fun crop(width: Int, height: Int, size: Size, x: Float, y: Float): Crop {
        require(width > 0 && height > 0)
        if (size.width == 0) return Crop(0,0,width,height)
        val ratio = size.width.toDouble() / size.height
        val w = minOf(width, (height * ratio).roundToInt()).coerceAtLeast(1)
        val h = minOf(height, (width / ratio).roundToInt()).coerceAtLeast(1)
        return Crop(((width-w)*x.coerceIn(0f,1f)).roundToInt(), ((height-h)*y.coerceIn(0f,1f)).roundToInt(), w,h)
    }
}
