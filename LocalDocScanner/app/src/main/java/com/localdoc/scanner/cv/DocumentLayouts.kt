package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object DocumentLayouts {
    /** Split at the suggested or user-adjusted gutter; preserve all content by default. */
    fun splitBookSpread(source: Bitmap, gutterRatio: Float = 0f, splitRatio: Float = 0.5f): Pair<Bitmap, Bitmap> {
        require(source.width >= 2 && splitRatio.isFinite() && gutterRatio.isFinite())
        val gutter = (source.width * gutterRatio.coerceIn(0f, 0.05f)).toInt()
        val middle = (source.width * splitRatio.coerceIn(0.2f, 0.8f)).toInt().coerceIn(1, source.width - 1)
        val leftWidth = (middle - gutter).coerceAtLeast(1)
        val rightStart = (middle + gutter).coerceAtMost(source.width - 1)
        val left = Bitmap.createBitmap(source, 0, 0, leftWidth, source.height)
        val right = Bitmap.createBitmap(source, rightStart, 0, source.width - rightStart, source.height)
        return left to right
    }

    /** 把证件正反面排到一张白色A4比例图片上，便于打印或归档。 */
    fun idCardSheet(front: Bitmap, back: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun draw(bitmap: Bitmap, centerY: Float) {
            val maxWidth = 1010f
            val maxHeight = 610f
            val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height, 1f)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = (output.width - width) / 2f
            val top = centerY - height / 2f
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), paint)
        }
        draw(front, 480f)
        draw(back, 1260f)
        return output
    }
}
