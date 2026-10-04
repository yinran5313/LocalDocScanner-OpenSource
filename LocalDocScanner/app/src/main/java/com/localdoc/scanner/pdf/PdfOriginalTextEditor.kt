/*
 * Content-stream adaptation of Apache PDFBox examples/util/RemoveAllText.java
 * (Apache-2.0). Uses PDFStreamParser/ContentStreamWriter, preserving operators.
 * Local changes: replace one Tj/TJ string, retain glyph advance and font, reject
 * unsupported fonts/ambiguous encodings instead of overlaying new text.
 */
package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

data class PdfTextObject(val key: String, val pageIndex: Int, val text: String, val editable: Boolean, val reason: String)
data class PdfTextChange(val key: String, val pageIndex: Int, val replacement: String)

object PdfOriginalTextEditor {
    private data class State(val font: PDFont? = null, val size: Float = 0f, val characterSpacing: Float = 0f, val wordSpacing: Float = 0f, val renderMode: Int = 0)
    private data class Glyphs(val text: String, val width: Float, val count: Int, val spaces: Int)
    private data class Run(val entry: PdfTextObject, val state: State, val bytes: ByteArray,
        val tokenIndex: Int, val arrayIndex: Int?, val glyphs: Glyphs?)
    fun list(input: File, pageIndex: Int): List<PdfTextObject> = PDDocument.load(input).use { doc ->
        require(pageIndex in 0 until doc.numberOfPages)
        parse(doc.getPage(pageIndex), pageIndex).second.map { run ->
            if (doc.currentAccessPermission.canModify()) run.entry else run.entry.copy(editable = false, reason = "PDF限制修改")
        }
    }
    fun apply(input: File, output: File, change: PdfTextChange): Boolean {
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "请使用新输出文件" }
        require(change.replacement.length <= 300 && '\n' !in change.replacement && '\r' !in change.replacement) { "每个文字对象最多300字，不能换行" }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            PDDocument.load(input).use { doc ->
                require(doc.currentAccessPermission.canModify()) { "PDF限制修改" }
                require(change.pageIndex in 0 until doc.numberOfPages)
                val page = doc.getPage(change.pageIndex)
                val (tokens, runs) = parse(page, change.pageIndex)
                val run = runs.singleOrNull { it.entry.key == change.key } ?: error("文字对象已经变化，请重新选择")
                require(run.entry.editable) { run.entry.reason }
                val font = run.state.font!!
                val old = run.glyphs!!
                val encoded = try { font.encode(change.replacement) } catch (e: Exception) {
                    throw IllegalArgumentException("原字体无法编码新文字，请使用填写文字或Office编辑；${e.message}", e)
                }
                val next = decode(font, encoded)
                val oldAdvance = old.width / 1000 * run.state.size + old.count * run.state.characterSpacing + old.spaces * run.state.wordSpacing
                val newAdvance = next.width / 1000 * run.state.size + next.count * run.state.characterSpacing + next.spaces * run.state.wordSpacing
                require(newAdvance <= oldAdvance * 1.05f + .5f) { "新文字超过原对象宽度，请缩短内容或使用其他编辑方式" }
                val compensation = (newAdvance - oldAdvance) / run.state.size * 1000
                if (run.arrayIndex == null) {
                    val array = COSArray().apply { add(COSString(encoded)); add(COSFloat(compensation)) }
                    tokens[run.tokenIndex] = array
                    tokens[run.tokenIndex + 1] = Operator.getOperator("TJ")
                } else {
                    val original = tokens[run.tokenIndex] as COSArray
                    val array = COSArray()
                    for (i in 0 until original.size()) {
                        if (i == run.arrayIndex) { array.add(COSString(encoded)); array.add(COSFloat(compensation)) }
                        else array.add(original.get(i))
                    }
                    tokens[run.tokenIndex] = array
                }
                val stream = PDStream(doc)
                stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
                page.setContents(stream)
                doc.save(temporary)
            }
            check(temporary.renameTo(output)) { "无法保存文字修改副本" }
            return true
        } finally { temporary.delete() }
    }
    private fun parse(page: PDPage, pageIndex: Int): Pair<MutableList<Any>, List<Run>> {
        val parser = PDFStreamParser(page); parser.parse()
        val tokens = parser.tokens.toMutableList()
        require(tokens.size <= 1_000_000) { "页面内容过于复杂" }
        val runs = mutableListOf<Run>()
        var state = State(); val stack = mutableListOf<State>(); var ordinal = 0
        fun add(string: COSString, token: Int, arrayIndex: Int?) {
            ordinal++
            val glyphs = state.font?.let { runCatching { decode(it, string.bytes) }.getOrNull() }
            val text = glyphs?.text.orEmpty()
            val reason = when {
                state.font == null -> "未找到字体"
                state.font!!.isVertical || state.font is PDType3Font -> "竖排或Type3字体暂不支持"
                state.renderMode !in 0..2 -> "不可见文字层或文字裁剪暂不修改"
                state.size <= 0 -> "字体尺寸无效"
                glyphs == null -> "字体缺少可靠的Unicode映射"
                text.isBlank() -> "空白文字"
                text.length > 300 -> "文字对象超过300字"
                else -> ""
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(string.bytes).joinToString("") { "%02x".format(it) }
            runs.add(Run(PdfTextObject("$pageIndex:$ordinal:$hash", pageIndex, text, reason.isBlank(), reason), state, string.bytes, token, arrayIndex, glyphs))
        }
        tokens.forEachIndexed { i, token ->
            if (token is Operator) when (token.name) {
                "q" -> stack.add(state.copy())
                "Q" -> if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                "Tf" -> state = state.copy(font = (tokens.getOrNull(i - 2) as? COSName)?.let { name -> runCatching { page.resources?.getFont(name) }.getOrNull() }, size = (tokens.getOrNull(i - 1) as? COSNumber)?.floatValue() ?: 0f)
                "Tc" -> state = state.copy(characterSpacing = (tokens.getOrNull(i - 1) as? COSNumber)?.floatValue() ?: 0f)
                "Tw" -> state = state.copy(wordSpacing = (tokens.getOrNull(i - 1) as? COSNumber)?.floatValue() ?: 0f)
                "Tr" -> state = state.copy(renderMode = (tokens.getOrNull(i - 1) as? COSNumber)?.intValue() ?: 0)
                "\"" -> state = state.copy(wordSpacing = (tokens.getOrNull(i - 3) as? COSNumber)?.floatValue() ?: 0f,
                    characterSpacing = (tokens.getOrNull(i - 2) as? COSNumber)?.floatValue() ?: 0f)
                "gs" -> (tokens.getOrNull(i - 1) as? COSName)?.let {
                    if (page.resources?.getExtGState(it)?.cosObject?.containsKey(COSName.FONT) == true) state = state.copy(font = null)
                }
                "Tj" -> (tokens.getOrNull(i - 1) as? COSString)?.let { add(it, i - 1, null) }
                "TJ" -> (tokens.getOrNull(i - 1) as? COSArray)?.let { array ->
                    for (j in 0 until array.size()) (array.get(j) as? COSString)?.let { add(it, i - 1, j) }
                }
            }
        }
        return tokens to runs
    }
    private fun decode(font: PDFont, bytes: ByteArray): Glyphs {
        val text = StringBuilder(); var width = 0f; var count = 0; var spaces = 0
        val input = ByteArrayInputStream(bytes)
        while (input.available() > 0) {
            val before = input.available(); val code = font.readCode(input)
            require(input.available() < before)
            val unicode = font.toUnicode(code) ?: error("字体缺少Unicode映射")
            text.append(unicode); width += font.getWidth(code); count++
            if (code == 32 && before - input.available() == 1) spaces++
        }
        return Glyphs(text.toString(), width, count, spaces)
    }
}
