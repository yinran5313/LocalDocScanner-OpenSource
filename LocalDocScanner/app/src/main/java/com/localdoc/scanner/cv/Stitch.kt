package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/** 长图拼接：多页纵向拼一张 */
object Stitch {
    /** Bounds pass followed by one decoded page at a time. Missing pages fail explicitly. */
    fun verticalFiles(files: List<java.io.File>, maxWidth: Int = 1080): Bitmap {
        val dimensions = files.map { file ->
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片：${file.name}" }
            options.outWidth to options.outHeight
        }
        val plan = BitmapBudget.stitchPlan(dimensions, maxWidth)
        val output = Bitmap.createBitmap(plan.width, plan.totalHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            var top = 0
            files.forEachIndexed { index, file ->
                val image = com.localdoc.scanner.util.ImageIo.loadFromFile(file, 2400)
                    ?: error("无法解码图片：${file.name}")
                try {
                    canvas.drawBitmap(image, null, android.graphics.RectF(0f, top.toFloat(), plan.width.toFloat(), (top + plan.heights[index]).toFloat()), null)
                    top += plan.heights[index]
                } finally { image.recycle() }
            }
            return output
        } catch (e: Exception) { output.recycle(); throw e }
    }

    fun vertical(images: List<Bitmap>, maxWidth: Int = 1080): Bitmap? {
        if (images.isEmpty()) return null
        val scaled = images.map { b ->
            val s = (maxWidth.toFloat() / b.width).coerceAtMost(1f)
            if (s < 1f) {
                Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
            } else {
                b
            }
        }
        try {
        val width = scaled.maxOf { it.width }
        val total = scaled.sumOf { it.height }
        require(total.toLong() * width <= BitmapBudget.MAX_PIXELS && total <= BitmapBudget.MAX_SIDE) { "长图过大，请分组拼接或使用PDF" }
        if (width <= 0 || total <= 0) return null
        val out = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        var y = 0
        scaled.forEach { bmp ->
            canvas.drawBitmap(bmp, ((width - bmp.width) / 2f), y.toFloat(), null)
            y += bmp.height
        }
        return out
        } finally {
            scaled.forEachIndexed { index, bitmap ->
                if (bitmap !== images[index]) bitmap.recycle()
            }
        }
    }
}
