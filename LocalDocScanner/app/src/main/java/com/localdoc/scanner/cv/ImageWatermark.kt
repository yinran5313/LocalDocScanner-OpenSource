package com.localdoc.scanner.cv

import android.graphics.*
import android.text.StaticLayout
import android.text.TextPaint

object ImageWatermark {
    fun render(bitmap: Bitmap, text: String): Bitmap {
        require(text.isNotBlank() && text.length <= 600) { "请填写水印内容" }
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result); val margin = (bitmap.width * .03f).coerceAtLeast(12f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = (bitmap.width * .027f).coerceIn(18f, 70f) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (bitmap.width - 2 * margin).toInt())
            .setIncludePad(false).setLineSpacing(3f, 1f).build()
        val top = (bitmap.height - margin - layout.height).coerceAtLeast(margin)
        canvas.drawRect(0f, top - margin / 2, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply { color = Color.argb(145, 0, 0, 0) })
        canvas.save(); canvas.translate(margin, top); layout.draw(canvas); canvas.restore()
        return result
    }
}
