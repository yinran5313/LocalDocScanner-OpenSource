package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import java.io.File
import java.security.MessageDigest
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

data class PdfAnnotationEntry(val key: String, val pageIndex: Int, val type: String,
    val contents: String, val editable: Boolean, val reason: String, val placement:NormalizedRect?=null)
data class PdfAnnotationChange(val key: String, val pageIndex: Int, val delete: Boolean, val contents: String, val placement:NormalizedRect?=null)

/** Operates on actual PDF annotations; page content, links and form widgets are preserved. */
object PdfAnnotationEditor {
    private val movable=setOf("Text","FreeText","Highlight","Underline","StrikeOut","Squiggly","Ink","Square","Circle","Line","Polygon","PolyLine","Stamp","Caret")
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
                    val placement=if(reason.isBlank() && annotation.subtype in movable) runCatching {PdfPageGeometry(page).normalizedRectangle(annotation.rectangle)}.getOrNull() else null
                    add(PdfAnnotationEntry(keys[index], pageIndex, annotation.subtype.orEmpty(), annotation.contents.orEmpty(), reason.isBlank(), reason,placement))
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
                    change.placement?.let {rect ->
                        require(annotation.subtype in movable && listOf(rect.x,rect.y,rect.width,rect.height).all {it.isFinite()} && rect.x>=0 && rect.y>=0 && rect.width>0 && rect.height>0 && rect.x+rect.width<=1.0001f && rect.y+rect.height<=1.0001f) {"批注范围无效"}
                        val geometry=PdfPageGeometry(page)
                        val a=geometry.point(rect.x,rect.y);val b=geometry.point(rect.x+rect.width,rect.y+rect.height)
                        move(annotation,geometry.rectangle(floatArrayOf(a.first,a.second,b.first,b.second)))
                    }
                    annotation.setModifiedDate(java.util.Calendar.getInstance())
                }
                doc.save(temporary)
            }
            check(temporary.renameTo(output)) { "无法保存批注副本" }
            return true
        } finally { temporary.delete() }
    }
    private fun move(annotation:PDAnnotationMarkup,target:PDRectangle) {
        val original=annotation.rectangle
        require(original!=null && original.width>0 && original.height>0) {"原批注范围无效"}
        val sx=target.width/original.width;val sy=target.height/original.height
        fun pairs(array:COSArray) {
            require(array.size()%2==0) {"批注坐标不完整"}
            for(i in 0 until array.size()) {
                val value=(array.getObject(i) as? COSNumber)?.floatValue() ?: error("批注坐标无效")
                require(value.isFinite())
                array.set(i,COSFloat(if(i%2==0) target.lowerLeftX+(value-original.lowerLeftX)*sx else target.lowerLeftY+(value-original.lowerLeftY)*sy))
            }
        }
        for(key in listOf("QuadPoints","Vertices","L","CL")) (annotation.cosObject.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)?.let(::pairs)
        (annotation.cosObject.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray)?.let {ink ->
            for(i in 0 until ink.size()) pairs(ink.getObject(i) as? COSArray ?: error("笔迹坐标无效"))
        }
        annotation.rectangle=target
        // Preserve the existing appearance; PDF renderers fit its BBox to the new Rect.
    }
    private fun key(annotation: PDAnnotation): String {
        val r = annotation.rectangle
        val value = listOf(annotation.annotationName.orEmpty(), annotation.subtype.orEmpty(),
            annotation.contents.orEmpty(), r?.lowerLeftX, r?.lowerLeftY, r?.upperRightX, r?.upperRightY)
            .joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
