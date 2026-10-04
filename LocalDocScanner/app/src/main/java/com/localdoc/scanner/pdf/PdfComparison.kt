package com.localdoc.scanner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.localdoc.scanner.export.PdfExporter
import com.localdoc.scanner.util.ImageIo
import java.io.File
import kotlin.math.abs

object PdfComparison {
    /** Aligned by page number; render differences, never claim semantic/layout equivalence. */
    fun compare(context: Context, first: File, second: File, output: File): Pair<Int, List<String>> {
        val aCount = PdfTools.pageCount(first)
        val bCount = PdfTools.pageCount(second)
        require(aCount > 0 && bCount > 0) { "PDF不可读取，请先解锁" }
        val temporary = File(context.cacheDir, "pdf-compare-${System.nanoTime()}").apply { mkdirs() }
        val notes = mutableListOf<String>()
        val images = mutableListOf<File>()
        try {
            for (page in 0 until maxOf(aCount, bCount)) {
                val a = if (page < aCount) PdfTools.renderPage(first, page, 1200) else null
                val b = if (page < bCount) PdfTools.renderPage(second, page, 1200) else null
                if (a == null && page < aCount || b == null && page < bCount) {
                    a?.recycle(); b?.recycle(); error("第${page + 1}页渲染失败")
                }
                val width = maxOf(a?.width ?: 1, b?.width ?: 1)
                val height = maxOf(a?.height ?: 1, b?.height ?: 1)
                val combined = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(combined)
                    canvas.drawColor(Color.WHITE)
                    a?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                    b?.let { canvas.drawBitmap(it, width.toFloat(), 0f, null) }
                    var different = 0
                    if (a == null || b == null) different = width * height
                    else {
                        val left = IntArray(width * height) { Color.WHITE }
                        val right = IntArray(width * height) { Color.WHITE }
                        a.getPixels(left, 0, width, 0, 0, a.width, a.height)
                        b.getPixels(right, 0, width, 0, 0, b.width, b.height)
                        for (i in left.indices) {
                            val p = left[i]; val q = right[i]
                            if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q)) > 90) {
                                different++
                                combined.setPixel(width + i % width, i / width, Color.rgb(230, 80, 80))
                            }
                        }
                    }
                    notes += "第${page + 1}页：" + if (a == null || b == null) "一份文件缺少此页" else "显示差异${"%.2f".format(different * 100.0 / (width * height))}%"
                    if (different > 0) {
                        val image = File(temporary, "${page + 1}.jpg")
                        check(ImageIo.saveJpeg(combined, image, 92))
                        images += image
                    }
                } finally { combined.recycle(); a?.recycle(); b?.recycle() }
            }
            if (images.isNotEmpty()) check(output.outputStream().use { PdfExporter.exportFiles(images, it, PdfExporter.PageSize.FIT_IMAGE, 2400) }) { "无法导出差异预览" }
            return images.size to notes
        } finally { temporary.listFiles()?.forEach { it.delete() }; temporary.delete() }
    }
}
