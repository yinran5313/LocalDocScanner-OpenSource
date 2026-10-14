package com.localdoc.scanner.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.localdoc.scanner.structure.CardTemplates
import com.localdoc.scanner.util.ImageIo
import java.io.File

object CardSheet {
    fun write(images: List<File>, template: CardTemplates.Template, out: File) {
        require(images.isNotEmpty() && images.size <= if (template.twoSides) 2 else 1) { "请按模板选择正面和反面" }
        val width = template.widthMm * 72f / 25.4f; val height = template.heightMm * 72f / 25.4f
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 10f }
            page.canvas.drawText("${template.label} · 打印时选择实际大小/100%", 36f, 34f, paint)
            images.forEachIndexed { i, file ->
                val bitmap = requireNotNull(ImageIo.loadFromFile(file, 2200)) { "证件图片无法读取" }
                try {
                    val x = (595f - width) / 2; val y = 75f + i * (height + 48f)
                    require(y + height <= 800f) { "证件尺寸超出A4" }
                    page.canvas.drawText(if (i == 0) "正面" else "反面", x, y - 8, paint)
                    page.canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally { bitmap.recycle() }
            }
            pdf.finishPage(page); out.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }
}
