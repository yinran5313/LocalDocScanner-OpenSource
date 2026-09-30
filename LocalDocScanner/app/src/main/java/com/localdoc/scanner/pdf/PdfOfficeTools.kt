package com.localdoc.scanner.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InputStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText
import kotlin.math.cos
import kotlin.math.sin

enum class PdfMarkup { HIGHLIGHT, UNDERLINE, STRIKEOUT }

data class PdfFormField(val name: String, val value: String, val type: String)
data class PdfInkPoint(val x: Float, val y: Float)

object PdfOfficeTools {
    fun decorate(
        context: Context,
        input: File,
        output: File,
        watermark: String,
        header: String,
        footer: String,
        addPageNumbers: Boolean,
        opacity: Float,
        watermarkBitmap: Bitmap? = null
    ): Boolean = edit(context, input, output) { document, font ->
        val total = document.numberOfPages
        val watermarkImage = watermarkBitmap?.let { LosslessFactory.createFromImage(document, it) }
        document.pages.forEachIndexed { index, page ->
            val geometry = PdfPageGeometry(page)
            val box = geometry.displayBox
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.transform(geometry.displayToPdf)
                stream.saveGraphicsState()
                val alpha = opacity.coerceIn(0.08f, 1f)
                stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = alpha
                    strokingAlphaConstant = alpha
                })
                stream.setNonStrokingColor(90, 90, 90)
                if (watermark.isNotBlank()) {
                    val safe = safeText(font, watermark, 80)
                    if (safe.isNotBlank()) {
                        stream.beginText()
                        stream.setFont(font, 34f)
                        val angle = Math.toRadians(32.0)
                        stream.setTextMatrix(
                            Matrix(
                                cos(angle).toFloat(), sin(angle).toFloat(),
                                -sin(angle).toFloat(), cos(angle).toFloat(),
                                box.width * 0.22f, box.height * 0.40f
                            )
                        )
                        stream.showText(safe)
                        stream.endText()
                    }
                }
                watermarkImage?.let { image ->
                    val drawWidth = box.width * 0.34f
                    val drawHeight = drawWidth * image.height / image.width.coerceAtLeast(1).toFloat()
                    stream.drawImage(
                        image,
                        (box.width - drawWidth) / 2f,
                        (box.height - drawHeight) / 2f,
                        drawWidth,
                        drawHeight
                    )
                }
                drawLineText(stream, font, safeText(font, header, 100), 10f, 36f, box.height - 28f)
                drawLineText(stream, font, safeText(font, footer, 100), 9f, 36f, 20f)
                if (addPageNumbers) {
                    drawLineText(stream, font, "${index + 1} / $total", 9f, box.width - 58f, 20f)
                }
                stream.restoreGraphicsState()
            }
        }
    }

    fun addText(
        context: Context,
        input: File,
        output: File,
        pageIndex: Int,
        text: String,
        xRatio: Float,
        yRatio: Float,
        fontSize: Float
    ): Boolean = edit(context, input, output) { document, font ->
        val page = document.getPage(pageIndex.coerceIn(0, document.numberOfPages - 1))
        val geometry = PdfPageGeometry(page)
        val box = geometry.displayBox
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.transform(geometry.displayToPdf)
            stream.setNonStrokingColor(20, 20, 20)
            drawLineText(
                stream,
                font,
                safeText(font, text, 300),
                fontSize.coerceIn(6f, 48f),
                box.width * xRatio.coerceIn(0f, 0.95f),
                box.height * (1f - yRatio.coerceIn(0.03f, 0.98f)) - fontSize.coerceIn(6f, 48f)
            )
        }
    }

    fun addMarkup(
        input: File,
        output: File,
        pageIndex: Int,
        kind: PdfMarkup,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float
    ): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val page = document.getPage(pageIndex.coerceIn(0, document.numberOfPages - 1))
            val geometry = PdfPageGeometry(page)
            val points = geometry.quad(xRatio, yRatio, widthRatio, heightRatio)
            val subtype = when (kind) {
                PdfMarkup.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                PdfMarkup.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                PdfMarkup.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
            }
            val annotation = PDAnnotationTextMarkup(subtype).apply {
                rectangle = geometry.rectangle(points)
                quadPoints = points
                color = PDColor(
                    if (kind == PdfMarkup.HIGHLIGHT) floatArrayOf(1f, 0.88f, 0.18f) else floatArrayOf(0.9f, 0.18f, 0.16f),
                    PDDeviceRGB.INSTANCE
                )
                contents = when (kind) {
                    PdfMarkup.HIGHLIGHT -> "高亮"
                    PdfMarkup.UNDERLINE -> "下划线"
                    PdfMarkup.STRIKEOUT -> "删除线"
                }
                setPrinted(true)
            }
            page.annotations.add(annotation)
            annotation.constructAppearances(document)
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrDefault(false)

    fun addNote(input: File, output: File, pageIndex: Int, text: String, xRatio: Float, yRatio: Float): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val page = document.getPage(pageIndex.coerceIn(0, document.numberOfPages - 1))
            val geometry = PdfPageGeometry(page)
            val note = PDAnnotationText().apply {
                rectangle = geometry.rectangle(geometry.quad(xRatio, yRatio, 28f / geometry.width, 28f / geometry.height))
                contents = text.take(1000)
                setName(PDAnnotationText.NAME_NOTE)
                setOpen(false)
                setPrinted(true)
                color = PDColor(floatArrayOf(1f, 0.8f, 0.12f), PDDeviceRGB.INSTANCE)
            }
            page.annotations.add(note)
            note.constructAppearances(document)
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrDefault(false)

    fun drawInk(
        input: File,
        output: File,
        pageIndex: Int,
        strokes: List<List<PdfInkPoint>>,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float
    ): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val page = document.getPage(pageIndex.coerceIn(0, document.numberOfPages - 1))
            val geometry = PdfPageGeometry(page)
            val x = xRatio.coerceIn(0f, .95f); val y = yRatio.coerceIn(0f, .95f)
            val w = widthRatio.coerceIn(.005f, 1f - x); val h = heightRatio.coerceIn(.005f, 1f - y)
            val paths = strokes.filter { it.size >= 2 }.map { stroke ->
                stroke.flatMap { point ->
                    require(point.x.isFinite() && point.y.isFinite())
                    val mapped = geometry.point(x + point.x.coerceIn(0f, 1f) * w, y + point.y.coerceIn(0f, 1f) * h)
                    listOf(mapped.first, mapped.second)
                }.toFloatArray()
            }
            require(paths.isNotEmpty()) { "没有可保存的笔迹" }
            // PDFBox's PDInkAppearanceHandler renders a real /Ink annotation with /AP.
            val ink = PDAnnotationMarkup().apply {
                cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
                rectangle = geometry.rectangle(geometry.quad(x, y, w, h))
                inkList = paths.toTypedArray()
                color = PDColor(floatArrayOf(.1f, .1f, .1f), PDDeviceRGB.INSTANCE)
                borderStyle = PDBorderStyleDictionary().apply { this.width = 1.8f }
                setPrinted(true)
            }
            page.annotations.add(ink)
            ink.constructAppearances(document)
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrDefault(false)

    fun addSignatureImage(
        input: File,
        output: File,
        pageIndex: Int,
        bitmap: Bitmap,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float
    ): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val page = document.getPage(pageIndex.coerceIn(0, document.numberOfPages - 1))
            val geometry = PdfPageGeometry(page)
            val box = geometry.displayBox
            val image = LosslessFactory.createFromImage(document, bitmap)
            val width = box.width * widthRatio.coerceIn(0.05f, 0.8f)
            val height = box.height * heightRatio.coerceIn(0.025f, 0.6f)
            val left = box.width * xRatio.coerceIn(0f, 1f - widthRatio)
            val bottom = box.height * (1f - yRatio.coerceIn(0f, 0.95f)) - height
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.transform(geometry.displayToPdf)
                stream.drawImage(image, left, bottom.coerceAtLeast(0f), width, height)
            }
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrDefault(false)

    fun formFields(input: File): List<PdfFormField> = runCatching {
        PDDocument.load(input).use { document ->
            val form = document.documentCatalog.acroForm ?: return@use emptyList()
            form.fieldTree.map { field ->
                PdfFormField(field.fullyQualifiedName.orEmpty(), field.valueAsString.orEmpty(), field.javaClass.simpleName)
            }.filter { it.name.isNotBlank() }.toList()
        }
    }.getOrDefault(emptyList())

    fun fillForm(context: Context, input: File, output: File, values: Map<String, String>): Boolean =
        context.assets.open("fonts/LXGWWenKai-Regular.ttf").use { fillForm(input, output, values, it) }

    fun fillForm(input: File, output: File, values: Map<String, String>, fontInput: InputStream? = null): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val form = document.documentCatalog.acroForm ?: error("这份PDF没有可填写表单")
            // PDFBox PDTextField.setValue -> constructAppearances. Variable text requires
            // a full embedded font, not the old value's font subset or viewer-generated text.
            val embedded = fontInput?.let { PDType0Font.load(document, it, false) }
            val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
            val fontName = embedded?.let { resources.add(it) }
            var needsViewerAppearance = false
            values.forEach { (name, value) ->
                val field = form.getField(name) ?: error("表单字段不存在：$name")
                check(!field.isReadOnly) { "字段只读：$name" }
                if (field is PDVariableText && fontName != null) {
                    field.widgets.forEach {
                        it.cosObject.removeItem(COSName.DA)
                        // Discard stale appearance font resources before regenerating /AP.
                        it.cosObject.removeItem(COSName.AP)
                    }
                    // Single-widget fields can share their COS dictionary with the widget.
                    // Set field /DA after clearing widget overrides, or it gets removed too.
                    field.defaultAppearance = "/${fontName.name} 0 Tf 0 g"
                    form.setNeedAppearances(false)
                }
                runCatching { field.setValue(value) }.onFailure {
                    if (fontInput != null) throw it
                    // 部分表单只嵌入了旧值所需的字体子集，中文无法现场生成外观。
                    // 仍保存真实字段值，并要求阅读器用系统字体生成显示外观。
                    field.cosObject.setString(COSName.V, value)
                    needsViewerAppearance = true
                }
            }
            if (needsViewerAppearance) form.setNeedAppearances(true)
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrElse {
        it.printStackTrace()
        false
    }

    private fun edit(
        context: Context,
        input: File,
        output: File,
        block: (PDDocument, PDType0Font) -> Unit
    ): Boolean = runCatching {
        output.parentFile?.mkdirs()
        PDDocument.load(input).use { document ->
            val font = context.assets.open("fonts/LXGWWenKai-Regular.ttf").use {
                PDType0Font.load(document, it, true)
            }
            block(document, font)
            document.save(output)
        }
        output.isFile && output.length() > 0
    }.getOrElse {
        it.printStackTrace()
        false
    }

    private fun safeText(font: PDType0Font, value: String, limit: Int): String = value
        .filter { char -> runCatching { font.hasGlyph(char.code) }.getOrDefault(false) }
        .take(limit)

    private fun drawLineText(
        stream: PDPageContentStream,
        font: PDType0Font,
        text: String,
        size: Float,
        x: Float,
        y: Float
    ) {
        if (text.isBlank()) return
        stream.beginText()
        stream.setFont(font, size)
        stream.newLineAtOffset(x, y)
        stream.showText(text)
        stream.endText()
    }
}
