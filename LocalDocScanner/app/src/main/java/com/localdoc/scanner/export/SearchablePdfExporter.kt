package com.localdoc.scanner.export

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import kotlin.math.min
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.ocr.OcrLayout
import com.tom_roush.pdfbox.util.Matrix

data class SearchablePdfPage(val image: File, val text: String, val boxes: List<OcrTextBox> = emptyList())

/** 扫描图作为可见页面，OCR文字作为不可见文字层。 */
object SearchablePdfExporter {
    fun export(context: Context, pages: List<SearchablePdfPage>, output: OutputStream): Boolean =
        context.assets.open("fonts/LXGWWenKai-Regular.ttf").use { font -> export(pages, output, font) }

    internal fun export(
        pages: List<SearchablePdfPage>,
        output: OutputStream,
        fontInput: InputStream,
        imageLoader: (PDDocument, File) -> PDImageXObject = { document, file ->
            file.inputStream().buffered().use { JPEGFactory.createFromStream(document, it) }
        }
    ): Boolean = runCatching {
        require(pages.isNotEmpty()) { "没有可导出的页面" }
        PDDocument().use { document ->
            val font = PDType0Font.load(document, fontInput, true)
            pages.forEach { item ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val image = imageLoader(document, item.image)
                val box = page.mediaBox
                val imageRatio = image.width.toFloat() / image.height.coerceAtLeast(1)
                val pageRatio = box.width / box.height
                val drawWidth: Float
                val drawHeight: Float
                if (imageRatio > pageRatio) {
                    drawWidth = box.width
                    drawHeight = drawWidth / imageRatio
                } else {
                    drawHeight = box.height
                    drawWidth = drawHeight * imageRatio
                }
                val left = (box.width - drawWidth) / 2f
                val bottom = (box.height - drawHeight) / 2f
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, left, bottom, drawWidth, drawHeight)
                    if (item.boxes.isNotEmpty()) {
                        item.boxes.filter(OcrLayout::valid).forEach { line ->
                            val text = fontText(font, line.text)
                            if (text.isNotBlank()) {
                                val textHeight = (line.bottom - line.top) * drawHeight
                                val size = textHeight.coerceAtLeast(1f)
                                val textWidth = font.getStringWidth(text) / 1000f * size
                                val horizontal = ((line.right - line.left) * drawWidth / textWidth.coerceAtLeast(0.01f) * 100f)
                                stream.beginText()
                                stream.setFont(font, size)
                                stream.setRenderingMode(RenderingMode.NEITHER)
                                stream.setHorizontalScaling(horizontal)
                                stream.setTextMatrix(Matrix.getTranslateInstance(left + line.left * drawWidth,
                                    bottom + (1f - line.bottom) * drawHeight + textHeight * 0.15f))
                                stream.showText(text)
                                stream.endText()
                            }
                        }
                    }
                    // Compatibility for previously recognized pages which have no coordinates.
                    val lines = if (item.boxes.isEmpty()) item.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() else emptyList()
                    if (lines.isNotEmpty()) {
                        val fontSize = min(11f, (drawHeight / (lines.size + 2)).coerceAtLeast(3f))
                        val leading = drawHeight / (lines.size + 1)
                        stream.beginText()
                        stream.setFont(font, fontSize)
                        stream.setRenderingMode(RenderingMode.NEITHER)
                        stream.newLineAtOffset(left + 6f, bottom + drawHeight - leading)
                        lines.forEachIndexed { index, raw ->
                            val line = fontText(font, raw)
                            val naturalWidth = font.getStringWidth(line) / 1000f * fontSize
                            stream.setHorizontalScaling(min(100f, (drawWidth - 12f).coerceAtLeast(1f) / naturalWidth.coerceAtLeast(1f) * 100f))
                            if (line.isNotBlank()) stream.showText(line)
                            if (index < lines.lastIndex) stream.newLineAtOffset(0f, -leading)
                        }
                        stream.endText()
                    }
                }
            }
            document.save(output)
        }
        true
    }.getOrElse {
        it.printStackTrace()
        false
    }

    private fun fontText(font: PDType0Font, raw: String): String = buildString {
        raw.codePoints().forEach { code ->
            if (code == 9) append(' ')
            else if (code >= 32 && code != 127) {
                if (runCatching { font.hasGlyph(code) }.getOrDefault(false)) appendCodePoint(code) else append('?')
            }
        }
    }
}
