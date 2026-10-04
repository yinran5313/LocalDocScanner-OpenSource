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
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

data class PdfCompressionResult(val optimizedImages: Int, val skippedImages: Int, val retainedOriginal: Boolean)

/**
 * PDF 工具：合并 / 拆分 / 压缩 / 转图片。
 *
 * 合并、拆分、文字提取和加密使用 PdfBox-Android，保留原页面内容与文字层。
 * 压缩只重新编码图片对象，保留文字、矢量、表单和批注；图片本身为有损处理。
 */
object PdfTools {

    fun pageCount(file: File): Int = withRenderer(file) { it.pageCount } ?: 0

    /** Bounded single-page rendering allows export tasks to checkpoint each page. */
    fun renderPageAtDpi(file: File, index: Int, dpi: Int): Bitmap? = withRenderer(file) { renderer ->
        if (index !in 0 until renderer.pageCount) return@withRenderer null
        renderer.openPage(index).use { page ->
            val scale = minOf(dpi.coerceIn(72, 300) / 72f, 5000f / maxOf(page.width, page.height))
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

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

    fun compress(input: File, output: File, dpi: Int, quality: Int): Boolean = try {
        compressPreservingContent(input, output, dpi, quality)
        output.isFile && output.length() > 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Use existing PDFBox resource APIs instead of rasterizing all page contents.
     * Masked, indexed, CMYK and 1-bit images are left alone to preserve their meaning.
     * A4-equivalent dpi is an image size budget, not a measured placement resolution.
     */
    fun compressPreservingContent(input: File, output: File, dpi: Int, quality: Int): PdfCompressionResult {
        require(input.canonicalFile != output.canonicalFile) { "输出不能覆盖原件" }
        require(dpi in 72..600 && quality in 1..100) { "压缩参数不合法" }
        output.parentFile?.mkdirs()
        val maxSide = (dpi * 11.7).toInt().coerceIn(800, 5000)
        val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val replacements = IdentityHashMap<COSBase, PDImageXObject>()
        var optimized = 0
        var skipped = 0
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            fun process(resources: PDResources?) {
                resources ?: return
                if (!visited.add(resources.cosObject)) return
                resources.xObjectNames.toList().forEach { name ->
                    when (val original = resources.getXObject(name)) {
                        is PDFormXObject -> process(original.resources)
                        is PDImageXObject -> {
                            val key = original.cosObject
                            val replaced = replacements[key]
                            if (replaced != null) {
                                resources.put(name, replaced)
                            } else if (visited.add(key)) {
                                if (original.isStencil || original.bitsPerComponent != 8 ||
                                    original.cosObject.containsKey(COSName.MASK) ||
                                    original.cosObject.containsKey(COSName.SMASK) ||
                                    original.colorSpace.name !in setOf("DeviceRGB", "DeviceGray") ||
                                    original.width.toLong() * original.height > 40_000_000L) {
                                    skipped++
                                    return@forEach
                                }
                                var bitmap: Bitmap? = null
                                try {
                                    val subsampling = ceil(maxOf(original.width, original.height).toDouble() / maxSide)
                                        .toInt().coerceAtLeast(1)
                                    bitmap = original.getImage(null, subsampling)
                                    val image = bitmap ?: error("无法解码图片")
                                    val candidate = JPEGFactory.createFromImage(document, image, quality / 100f)
                                    // Keep the original object if JPEG would be larger.
                                    if (candidate.cosObject.length < original.cosObject.length) {
                                        candidate.interpolate = original.interpolate
                                        resources.put(name, candidate)
                                        replacements[key] = candidate
                                        optimized++
                                    } else skipped++
                                } catch (_: Exception) {
                                    skipped++
                                } finally {
                                    bitmap?.recycle()
                                }
                            }
                        }
                    }
                }
            }
            document.pages.forEach { process(it.resources) }
            document.save(output)
        }
        val retained = output.length() >= input.length() || optimized == 0
        if (retained) input.copyTo(output, overwrite = true)
        return PdfCompressionResult(if (retained) 0 else optimized, skipped, retained)
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
        require(listOf(xRatio, yRatio, widthRatio, heightRatio).all { it.isFinite() }) { "打码位置不合法" }
        streamRasterPages(input, output, dpi, 92, pageIndex) { index, target ->
            if (index == pageIndex) {
                val x = xRatio.coerceIn(0f, 0.98f)
                val y = yRatio.coerceIn(0f, 0.98f)
                val left = target.width * x
                val top = target.height * y
                val right = left + target.width * widthRatio.coerceIn(0.02f, 1f - x)
                val bottom = top + target.height * heightRatio.coerceIn(0.02f, 1f - y)
                Canvas(target).drawRect(RectF(left, top, right, bottom), Paint().apply { color = Color.BLACK })
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** 每页导出一张图 */
    fun toImages(input: File, outDir: File, dpi: Int = 150): List<File> {
        val files = mutableListOf<File>()
        outDir.mkdirs()
        withRenderer(input) { renderer ->
            for (index in 0 until renderer.pageCount) {
                val bmp = rasterPage(renderer, index, dpi)
                try {
                    val out = File(outDir, "${input.nameWithoutExtension}_${index + 1}.jpg")
                    check(ImageIo.saveJpeg(bmp, out, 92)) { "第${index + 1}页保存失败" }
                    files.add(out)
                } finally { bmp.recycle() }
            }
        } ?: error("PDF转图片失败；原件已保留")
        return files
    }

    private fun rasterPage(renderer: PdfRenderer, index: Int, dpi: Int): Bitmap = renderer.openPage(index).use { page ->
        require(dpi in 72..600) { "分辨率不合法" }
        val scale = (dpi / 72f).coerceAtMost(5000f / maxOf(page.width, page.height))
        Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            catch (e: Exception) { bitmap.recycle(); throw e }
        }
    }

    private fun streamRasterPages(input: File, output: File, dpi: Int, quality: Int, requiredPage: Int,
        transform: (Int, Bitmap) -> Unit): Boolean {
        require(input.canonicalFile != output.canonicalFile) { "输出不能覆盖原件" }
        output.parentFile?.mkdirs()
        return withRenderer(input) { renderer ->
            require(renderer.pageCount > 0) { "PDF没有页面" }
            require(requiredPage in 0 until renderer.pageCount) { "打码页码不合法" }
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                for (index in 0 until renderer.pageCount) {
                    val bitmap = rasterPage(renderer, index, dpi)
                    try {
                        transform(index, bitmap)
                        val size = renderer.openPage(index).use { it.width to it.height }
                        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(size.first.toFloat(), size.second.toFloat()))
                        document.addPage(page)
                        val image = JPEGFactory.createFromImage(document, bitmap, quality / 100f)
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { content ->
                            content.drawImage(image, 0f, 0f, size.first.toFloat(), size.second.toFloat())
                        }
                    } finally { bitmap.recycle() }
                }
                document.save(output)
            }
            output.isFile && output.length() > 0
        } ?: false
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
