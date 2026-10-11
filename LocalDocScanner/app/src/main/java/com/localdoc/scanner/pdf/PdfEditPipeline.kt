package com.localdoc.scanner.pdf
import android.content.Context
import com.localdoc.scanner.jobs.PendingPdfEdit
import com.localdoc.scanner.util.ImageIo
import java.io.File

/** Preview and background export must use precisely the same operations. */
object PdfEditPipeline {
    fun apply(context:Context, source:File, target:File, edit:PendingPdfEdit):Boolean = when(edit.operation) {
        0 -> { val image=edit.watermarkUri?.let { ImageIo.loadFromUri(context,it,1600) }
            try { PdfOfficeTools.decorate(context,source,target,edit.watermark,edit.header,edit.footer,edit.addPageNumbers,edit.opacity,image,edit.decoration ?: PdfDecorationOptions()) } finally { image?.recycle() } }
        1 -> edit.text.isNotBlank() && PdfOfficeTools.addText(context,source,target,edit.pageIndex,edit.text,edit.x,edit.y,13f)
        2 -> PdfOfficeTools.addMarkup(source,target,edit.pageIndex,edit.markup,edit.x,edit.y,edit.width,edit.height)
        3 -> edit.text.isNotBlank() && PdfOfficeTools.addNote(source,target,edit.pageIndex,edit.text,edit.x,edit.y)
        4 -> edit.strokes.any { it.size >= 2 } && PdfOfficeTools.drawInk(source,target,edit.pageIndex,edit.strokes,edit.x,edit.y,edit.width,edit.height)
        5 -> { val image=edit.signatureUri?.let { ImageIo.loadFromUri(context,it,1600) } ?: error("签名图片不可读取")
            try { PdfOfficeTools.addSignatureImage(source,target,edit.pageIndex,image,edit.x,edit.y,edit.width,edit.height) } finally { image.recycle() } }
        6 -> edit.formValues.isNotEmpty() && PdfOfficeTools.fillForm(context,source,target,edit.formValues)
        7 -> PdfTools.redactPermanent(source,target,edit.pageIndex,edit.x,edit.y,edit.width,edit.height)
        8 -> edit.annotationChange?.let { PdfAnnotationEditor.apply(source,target,it) } ?: false
        9 -> edit.textChange?.let { PdfOriginalTextEditor.apply(source,target,it) } ?: false
        else -> error("未知PDF操作")
    }
}
