package com.localdoc.scanner.export

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.widget.TextView
import android.view.View
import android.util.TypedValue
import com.localdoc.scanner.util.AtomicFiles
import io.noties.markwon.Markwon
import java.io.File

object MarkdownPdf {
    fun write(context: Context, text: String, target: File, sourceDirectory: File? = null) {
        require(text.isNotBlank() && text.length <= 200_000) { "PDF导出限20万字符，请分段处理" }
        // TablePlugin binds spans to TextView width in its rendering hooks.
        // Using toMarkdown alone bypasses those hooks and misplaces row borders.
        val renderer = Markwon.builder(context).usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(
            io.noties.markwon.ext.tables.TableTheme.buildWithDefaults(context).tableCellPadding(6).tableBorderWidth(1).tableBorderColor(Color.GRAY).build())).build()
        val view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX,13f); setTextColor(Color.BLACK); includeFontPadding=false
            setPadding(0,0,0,0); setLineSpacing(0f,1f); layout(0,0,523,0)
        }
        renderer.setMarkdown(view,text)
        repeat(2) {
            view.forceLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(523,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            view.layout(0,0,523,view.measuredHeight)
        }
        val layout = requireNotNull(view.layout) { "Markdown排版失败" }
        val document = PdfDocument()
        try {
            var start = 0; var page = 0
            while (start < layout.lineCount) {
                val top = layout.getLineTop(start); var end = start + 1
                require(layout.getLineBottom(start)-top<=770) { "单段或表格行过高，请拆分长单元格后导出" }
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= 770) end++
                val bottom = layout.getLineBottom(end - 1)
                val output = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++page).create())
                output.canvas.drawColor(Color.WHITE); output.canvas.save(); output.canvas.clipRect(36, 36, 559, 806)
                output.canvas.translate(36f, (36 - top).toFloat()); layout.draw(output.canvas); output.canvas.restore()
                document.finishPage(output); start = end
                require(bottom > top && page <= 1000) { "文档页数过多" }
            }
            MarkdownLocalAssets.files(text,sourceDirectory).forEach { image ->
                require(page<1000) { "文档页数过多" }
                val bitmap=requireNotNull(com.localdoc.scanner.util.ImageIo.loadExactFromFile(image,2600)) { "原页附件无法读取" }
                try {
                    val output=document.startPage(PdfDocument.PageInfo.Builder(595,842,++page).create())
                    output.canvas.drawColor(Color.WHITE)
                    output.canvas.drawText("原页附件：${image.name}",36f,45f,android.graphics.Paint().apply { color=Color.DKGRAY; textSize=12f })
                    val scale=minOf(523f/bitmap.width,735f/bitmap.height)
                    val w=bitmap.width*scale; val h=bitmap.height*scale
                    output.canvas.drawBitmap(bitmap,null,android.graphics.RectF((595-w)/2,60f,(595+w)/2,60+h),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(output)
                } finally { bitmap.recycle() }
            }
            AtomicFiles.write(target) { it.outputStream().use(document::writeTo) }
        } finally { document.close() }
    }
}
