package com.localdoc.scanner.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File

/** Passwords live only in the opening call. Working copies require an explicit UI action. */
class PdfReadSession private constructor(private val file: File, private val doc: PDDocument) : Closeable {
    val pageCount: Int = doc.numberOfPages
    val encrypted: Boolean = doc.isEncrypted
    val signatureCount: Int = doc.signatureDictionaries.size
    val canCreateWorkingCopy: Boolean get() = doc.currentAccessPermission.let {
        it.isOwnerPermission || (it.canModify() && it.canExtractContent() && it.canAssembleDocument())
    }
    private var closed = false
    private var nativeRenderer: android.graphics.pdf.PdfRenderer? = null
    private var triedNative = false
    @Synchronized fun pageAspectRatios(): List<Float> {
        check(!closed)
        return (0 until pageCount).map { index ->
            val page=doc.getPage(index); val box=page.cropBox
            val rotated=page.rotation % 180 != 0
            val ratio=if(rotated) box.height/box.width else box.width/box.height
            if(ratio.isFinite() && ratio>0f) ratio.coerceIn(0.1f,10f) else 0.707f
        }
    }
    /** Never overwrite the source or an existing output; a failed save leaves no partial PDF. */
    @Synchronized fun createWorkingCopy(output: File): File {
        check(!closed)
        require(canCreateWorkingCopy) { "此密码仅允许阅读；请使用拥有修改权限的密码" }
        require(output.canonicalFile != file.canonicalFile && !output.exists()) { "工作副本必须使用新文件" }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.${java.util.UUID.randomUUID()}.part")
        val previous = doc.isAllSecurityToBeRemoved
        try {
            doc.isAllSecurityToBeRemoved = true
            doc.save(temporary)
            PDDocument.load(temporary).use { check(!it.isEncrypted && it.numberOfPages == pageCount) }
            check(temporary.renameTo(output)) { "无法保存工作副本" }
            return output
        } finally {
            doc.isAllSecurityToBeRemoved = previous
            temporary.delete()
        }
    }
    @Synchronized fun pageText(index: Int): String {
        check(!closed)
        require(doc.currentAccessPermission.canExtractContent()) { "此PDF限制文字提取" }
        return PDFTextStripper().apply {
            sortByPosition = true
            startPage = index + 1
            endPage = index + 1
        }.getText(doc)
    }
    @Synchronized fun render(index: Int, maxSide: Int = 1400): Bitmap? {
        if (closed || index !in 0 until pageCount) return null
        if (!encrypted) {
            if(!triedNative) {
                triedNative=true
                val fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                try { nativeRenderer=android.graphics.pdf.PdfRenderer(fd) } catch(_:Exception) { fd.close() }
            }
            nativeRenderer?.let { renderer ->
                var bitmap:Bitmap?=null
                try {
                    renderer.openPage(index).use { page ->
                        val scale=maxSide.toFloat()/maxOf(page.width,page.height).coerceAtLeast(1)
                        bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                        bitmap!!.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap!!,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                    return bitmap
                } catch(_:Exception) { bitmap?.recycle() }
            }
        }
        val box = doc.getPage(index).cropBox
        val scale = (maxSide / maxOf(box.width, box.height)).coerceIn(0.1f, 3f)
        return PDFRenderer(doc).apply { isSubsamplingAllowed = true }.renderImage(index, scale)
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; try { nativeRenderer?.close() } finally { nativeRenderer=null; doc.close() } }
    }
    companion object {
        fun open(file: File, password: String = ""): PdfReadSession = PdfReadSession(
            file, PDDocument.load(file, password, MemoryUsageSetting.setupMixed(16L * 1024 * 1024))
        )
    }
}
