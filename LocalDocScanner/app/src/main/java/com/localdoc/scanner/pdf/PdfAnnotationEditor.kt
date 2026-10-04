package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import java.io.File
import java.security.MessageDigest

data class PdfAnnotationEntry(val key: String, val pageIndex: Int, val type: String,
    val contents: String, val editable: Boolean, val reason: String)
data class PdfAnnotationChange(val key: String, val pageIndex: Int, val delete: Boolean, val contents: String)

/** Operates on actual PDF annotations; page content, links and form widgets are preserved. */
object PdfAnnotationEditor {
    fun list(input: File): List<PdfAnnotationEntry> = PDDocument.load(input).use { doc ->
        buildList {
            doc.pages.forEachIndexed { pageIndex, page ->
                val annotations = page.annotations
                val keys = annotations.map(::key)
                annotations.forEachIndexed { index, annotation ->
                    val reason = when {
                        !doc.currentAccessPermission.canModifyAnnotations() -> "PDF限制批注修改"
                        annotation.isReadOnly || annotation.isLocked || annotation.isLockedContents -> "批注已锁定"
                        annotation !is PDAnnotationMarkup -> "链接、表单或其他对象，请使用对应工具"
                        keys.count { it == keys[index] } != 1 -> "存在相同批注，无法可靠区分"
                        else -> ""
                    }
                    add(PdfAnnotationEntry(keys[index], pageIndex, annotation.subtype.orEmpty(), annotation.contents.orEmpty(), reason.isBlank(), reason))
                }
            }
        }
    }
    fun apply(input: File, output: File, change: PdfAnnotationChange): Boolean {
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "请使用新的输出文件" }
        require(change.delete || change.contents.length <= 1000) { "批注内容不能超过1000字" }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            PDDocument.load(input).use { doc ->
                require(doc.currentAccessPermission.canModifyAnnotations()) { "PDF限制批注修改" }
                require(change.pageIndex in 0 until doc.numberOfPages) { "批注页面不存在" }
                val page = doc.getPage(change.pageIndex)
                val matching = page.annotations.filter { key(it) == change.key }
                require(matching.size == 1) { "批注已经变化或已被移除，请重新选择" }
                val annotation = matching.single()
                require(annotation is PDAnnotationMarkup && !annotation.isReadOnly && !annotation.isLocked && !annotation.isLockedContents) { "此对象不能作为普通批注修改" }
                if (change.delete) {
                    val popup = annotation.popup
                    page.annotations = page.annotations.filter { it.cosObject !== annotation.cosObject && (popup == null || it.cosObject !== popup.cosObject) }
                } else {
                    // Contents is the comment attached to highlights/ink; it is not the page's original text.
                    annotation.contents = change.contents
                    annotation.setModifiedDate(java.util.Calendar.getInstance())
                }
                doc.save(temporary)
            }
            check(temporary.renameTo(output)) { "无法保存批注副本" }
            return true
        } finally { temporary.delete() }
    }
    private fun key(annotation: PDAnnotation): String {
        val r = annotation.rectangle
        val value = listOf(annotation.annotationName.orEmpty(), annotation.subtype.orEmpty(),
            annotation.contents.orEmpty(), r?.lowerLeftX, r?.lowerLeftY, r?.upperRightX, r?.upperRightY)
            .joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
