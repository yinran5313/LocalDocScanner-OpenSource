package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.localdoc.scanner.util.ImageIo
import kotlin.math.ceil
import kotlin.math.abs

internal object ImageBatchProcessor {
    data class Result(val bitmap: Bitmap, val note: String)
    fun render(source: Bitmap, rotation: Int, deskew: Boolean, filter: ScanFilter): Result {
        val owned = mutableListOf<Bitmap>()
        var result: Bitmap? = null
        try {
            var image = ImageIo.rotate(source, rotation * 90f).also { owned.add(it) }
            var note = ""
            if (deskew) {
                val angle = OpenCvDocument.estimateSkew(image)
                note = when {
                    angle == null -> "方向不明确，保留原角度"
                    abs(angle) < .2 -> "文字行已接近水平"
                    else -> {
                        image = rotateOnWhite(image, -angle.toFloat()).also { owned.add(it) }
                        "已纠偏 %.1f°，完整保留四周内容".format(-angle)
                    }
                }
            }
            result = applyFilter(image, filter).also { owned.add(it) }
            return Result(result, note)
        } finally { owned.distinct().filter { it !== source && it !== result }.forEach { it.recycle() } }
    }
    private fun rotateOnWhite(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees) }
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)
        val width = ceil(bounds.width()).toInt().coerceAtLeast(1)
        val height = ceil(bounds.height()).toInt().coerceAtLeast(1)
        require(width.toLong() * height <= 20_000_000) { "纠偏图片过大，请降低尺寸" }
        matrix.postTranslate(-bounds.left, -bounds.top)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
            return out
        } catch (e: Exception) { out.recycle(); throw e }
    }
}
