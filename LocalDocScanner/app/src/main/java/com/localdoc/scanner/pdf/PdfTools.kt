package com.localdoc.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.localdoc.scanner.util.ImageIo
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

/**
 * PDF 工具：合并 / 拆分 / 压缩 / 转图片。
 *
 * 合并、拆分、文字提取和加密使用 PdfBox-Android，保留原页面内容与文字层。
 * 压缩仍是明确标注的有损栅格模式，适合扫描PDF，不冒充无损压缩。
 */
object PdfTools {

    fun pageCount(file: File): Int = withRenderer(file) { it.pageCount } ?: 0

    fun render(file: File, dpi: Int = 150): List<Bitmap> {
        val out = mutableListOf<Bitmap>()
        withRenderer(file) { renderer ->
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val w = page.width * dpi / 72
                val h = page.height * dpi / 72
                val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                out.add(bmp)
            }
        }
        return out
    }

    fun renderPage(file: File, index: Int, maxSide: Int = 1800): Bitmap? = withRenderer(file) { renderer ->
        if (index !in 0 until renderer.pageCount) return@withRenderer null
        val page = renderer.openPage(index)
        try {
            val scale = (maxSide.toFloat() / maxOf(page.width, page.height)).coerceAtMost(3f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    /** 多份 PDF 合成一份 */
    fun merge(inputs: List<File>, output: File): Boolean = try {
        if (inputs.isEmpty()) {
            false
        } else {
            output.parentFile?.mkdirs()
            val merger = PDFMergerUtility().apply {
                destinationFileName = output.absolutePath
                inputs.forEach { addSource(it) }
            }
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            output.exists() && output.length() > 0
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** 按页区间拆成多份，ranges 为 1 起的闭区间 */
    fun split(input: File, ranges: List<IntRange>, outDir: File): List<File> {
        val results = mutableListOf<File>()
        outDir.mkdirs()
        PDDocument.load(input).use { source ->
            ranges.forEachIndexed { index, range ->
                val valid = range.filter { it in 1..source.numberOfPages }
                if (valid.isEmpty()) return@forEachIndexed
                val out = File(outDir, "${input.nameWithoutExtension}_${index + 1}.pdf")
                PDDocument().use { target ->
                    valid.forEach { pageNumber -> target.importPage(source.getPage(pageNumber - 1)) }
                    target.save(out)
                }
                if (out.exists() && out.length() > 0) results.add(out)
            }
        }
        return results
    }

    fun extractText(input: File): String = try {
        PDDocument.load(input).use { document -> PDFTextStripper().getText(document).trim() }
    } catch (e: Exception) {
        ""
    }

    fun encrypt(input: File, output: File, userPassword: String, ownerPassword: String = userPassword): Boolean = try {
        require(userPassword.isNotEmpty()) { "密码不能为空" }
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val permission = AccessPermission()
            val policy = StandardProtectionPolicy(ownerPassword, userPassword, permission).apply {
                encryptionKeyLength = 256
                permissions = permission
            }
            document.protect(policy)
            document.save(output)
        }
        output.exists() && output.length() > 0
    } catch (e: Exception) {
        false
    }

    /** 降采样重编码实现压缩 */
    fun compress(input: File, output: File, dpi: Int, quality: Int): Boolean = try {
        val pages = render(input, dpi).map { bmp ->
            val tmp = File(output.parentFile, "tmp_${System.currentTimeMillis()}_${bmp.width}.jpg")
            ImageIo.saveJpeg(bmp, tmp, quality)
            val decoded = ImageIo.loadFromFile(tmp, 4000) ?: bmp
            tmp.delete()
            if (decoded !== bmp) bmp.recycle()
            decoded
        }
        val ok = write(pages, output)
        pages.forEach { it.recycle() }
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * 永久打码：将页面栅格化后再写入新PDF，目标区域的原文字和对象不会保留在输出文件中。
     * 为保证无法通过删除批注恢复，输出整份PDF都会变成图像页面。
     */
    fun redactPermanent(
        input: File,
        output: File,
        pageIndex: Int,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float,
        dpi: Int = 180
    ): Boolean = try {
        val pages = render(input, dpi)
        val target = pages.getOrNull(pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
            ?: error("PDF没有可打码页面")
        val left = target.width * xRatio.coerceIn(0f, 0.95f)
        val top = target.height * yRatio.coerceIn(0f, 0.95f)
        val right = (left + target.width * widthRatio.coerceIn(0.02f, 1f - xRatio)).coerceAtMost(target.width.toFloat())
        val bottom = (top + target.height * heightRatio.coerceIn(0.02f, 1f - yRatio)).coerceAtMost(target.height.toFloat())
        Canvas(target).drawRect(RectF(left, top, right, bottom), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        val ok = write(pages, output)
        pages.forEach { it.recycle() }
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** 每页导出一张图 */
    fun toImages(input: File, outDir: File, dpi: Int = 150): List<File> {
        val pages = render(input, dpi)
        val files = mutableListOf<File>()
        pages.forEachIndexed { index, bmp ->
            val out = File(outDir, "${input.nameWithoutExtension}_${index + 1}.jpg")
            if (ImageIo.saveJpeg(bmp, out, 92)) files.add(out)
        }
        pages.forEach { it.recycle() }
        return files
    }

    private fun write(pages: List<Bitmap>, output: File): Boolean = try {
        output.parentFile?.mkdirs()
        val doc = PdfDocument()
        pages.forEachIndexed { index, bmp ->
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index + 1).create()
            val page = doc.startPage(info)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
        }
        FileOutputStream(output).use { doc.writeTo(it) }
        doc.close()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T? = try {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            block(renderer)
        } finally {
            renderer.close()
            fd.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
