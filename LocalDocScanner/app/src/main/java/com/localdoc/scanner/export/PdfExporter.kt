package com.localdoc.scanner.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfDocument.PageInfo
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import com.localdoc.scanner.util.ImageIo
import kotlin.math.min

/** 使用系统 PdfDocument 导出，零第三方依赖 */
object PdfExporter {

    enum class PageSize(val widthPt: Int, val heightPt: Int) {
        A4(595, 842),
        LETTER(612, 792),
        FIT_IMAGE(0, 0)
    }

    fun export(images: List<Bitmap>, output: File, pageSize: PageSize = PageSize.A4): Boolean {
        if (images.isEmpty()) return false
        return try {
            val document = PdfDocument()
            images.forEachIndexed { index, bitmap ->
                val info = when (pageSize) {
                    PageSize.FIT_IMAGE -> PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    else -> {
                        val width = pageSize.widthPt
                        val height = pageSize.heightPt
                        PageInfo.Builder(width, height, index + 1).create()
                    }
                }
                val page = document.startPage(info)
                drawBitmapOnPage(page.canvas, bitmap, info.pageWidth, info.pageHeight)
                document.finishPage(page)
            }
            FileOutputStream(output).use { document.writeTo(it) }
            document.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 逐页解码，避免多页文档把所有大图同时放进内存。 */
    fun exportFiles(
        imageFiles: List<File>,
        output: OutputStream,
        pageSize: PageSize = PageSize.A4,
        maxImageSide: Int = 3200
    ): Boolean {
        if (imageFiles.isEmpty()) return false
        val document = PdfDocument()
        return try {
            var written = 0
            imageFiles.forEach { file ->
                val bitmap = ImageIo.loadFromFile(file, maxImageSide) ?: return@forEach
                val info = when (pageSize) {
                    PageSize.FIT_IMAGE -> PageInfo.Builder(bitmap.width, bitmap.height, written + 1).create()
                    else -> PageInfo.Builder(pageSize.widthPt, pageSize.heightPt, written + 1).create()
                }
                val page = document.startPage(info)
                drawBitmapOnPage(page.canvas, bitmap, info.pageWidth, info.pageHeight)
                document.finishPage(page)
                bitmap.recycle()
                written++
            }
            if (written == 0) return false
            document.writeTo(output)
            output.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            document.close()
        }
    }

    private fun drawBitmapOnPage(canvas: Canvas, bitmap: Bitmap, pageWidth: Int, pageHeight: Int) {
        val scale = min(
            pageWidth.toFloat() / bitmap.width,
            pageHeight.toFloat() / bitmap.height
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (pageWidth - drawWidth) / 2f
        val top = (pageHeight - drawHeight) / 2f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), null)
    }
}
