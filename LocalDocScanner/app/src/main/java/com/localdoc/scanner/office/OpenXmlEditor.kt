package com.localdoc.scanner.office

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

enum class OfficeKind(val label: String) { WORD("Word"), SHEET("表格"), SLIDES("PPT") }

data class OfficeEditUnit(
    val id: String,
    val section: String,
    val label: String,
    val text: String
)

data class OpenXmlDocument(
    val kind: OfficeKind,
    val units: List<OfficeEditUnit>
)

/**
 * 对现代 OpenXML 文件做有限且可回写的文字/单元格编辑。
 * 保存时只替换发生变化的 XML 部件，其余 ZIP 条目逐字节复制。
 */
object OpenXmlEditor {
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private const val ACCESS_EXTERNAL_STYLESHEET = "http://javax.xml.XMLConstants/property/accessExternalStylesheet"

    fun supports(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in setOf("docx", "xlsx", "pptx")

    fun read(file: File): OpenXmlDocument {
        val extension = file.extension.lowercase()
        ZipFile(file).use { zip ->
            return when (extension) {
                "docx" -> OpenXmlDocument(OfficeKind.WORD, readWord(zip))
                "xlsx" -> OpenXmlDocument(OfficeKind.SHEET, readSheet(zip))
                "pptx" -> OpenXmlDocument(OfficeKind.SLIDES, readSlides(zip))
                else -> error("暂不支持 .$extension")
            }
        }
    }

    fun save(file: File, output: File, edits: Map<String, String>) {
        output.parentFile?.mkdirs()
        ZipFile(file).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val replacement = if (!entry.isDirectory) replaceEntry(zip, entry.name, edits) else null
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) {
                        if (replacement != null) out.write(replacement) else zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    private fun readWord(zip: ZipFile): List<OfficeEditUnit> {
        val name = "word/document.xml"
        val document = parse(zip, name)
        val paragraphs = document.getElementsByTagNameNS(WORD_NS, "p")
        return (0 until paragraphs.length).mapNotNull { index ->
            val texts = descendantElements(paragraphs.item(index), WORD_NS, "t")
            val value = texts.joinToString("") { it.textContent }
            value.takeIf { it.isNotBlank() }?.let {
                OfficeEditUnit("$name#$index", "正文", "段落 ${index + 1}", value)
            }
        }
    }

    private fun readSheet(zip: ZipFile): List<OfficeEditUnit> {
        val shared = readSharedStrings(zip)
        val names = zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfter("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
        return names.flatMapIndexed { sheetIndex, name ->
            val document = parse(zip, name)
            val cells = document.getElementsByTagNameNS(SHEET_NS, "c")
            (0 until cells.length).mapNotNull { index ->
                val cell = cells.item(index) as Element
                val formula = firstChild(cell, SHEET_NS, "f")?.textContent
                val valueNode = firstChild(cell, SHEET_NS, "v")
                val value = when {
                    !formula.isNullOrBlank() -> "=$formula"
                    cell.getAttribute("t") == "s" -> shared.getOrNull(valueNode?.textContent?.toIntOrNull() ?: -1).orEmpty()
                    cell.getAttribute("t") == "inlineStr" -> descendantElements(cell, SHEET_NS, "t").joinToString("") { it.textContent }
                    else -> valueNode?.textContent.orEmpty()
                }
                value.takeIf { it.isNotBlank() }?.let {
                    OfficeEditUnit("$name#$index", "工作表 ${sheetIndex + 1}", cell.getAttribute("r").ifBlank { "单元格 ${index + 1}" }, value)
                }
            }
        }
    }

    private fun readSlides(zip: ZipFile): List<OfficeEditUnit> {
        val names = zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfter("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
        return names.flatMapIndexed { slideIndex, name ->
            val document = parse(zip, name)
            val paragraphs = document.getElementsByTagNameNS(DRAWING_NS, "p")
            (0 until paragraphs.length).mapNotNull { index ->
                val value = descendantElements(paragraphs.item(index), DRAWING_NS, "t").joinToString("") { it.textContent }
                value.takeIf { it.isNotBlank() }?.let {
                    OfficeEditUnit("$name#$index", "第 ${slideIndex + 1} 页", "文本 ${index + 1}", value)
                }
            }
        }
    }

    private fun replaceEntry(zip: ZipFile, name: String, edits: Map<String, String>): ByteArray? {
        val relevant = edits.filterKeys { it.startsWith("$name#") }
        if (relevant.isEmpty()) return null
        val document = parse(zip, name)
        when {
            name == "word/document.xml" -> updateTextParagraphs(document, WORD_NS, relevant)
            name.startsWith("ppt/slides/slide") -> updateTextParagraphs(document, DRAWING_NS, relevant)
            name.startsWith("xl/worksheets/sheet") -> updateCells(document, relevant)
            else -> return null
        }
        return serialize(document)
    }

    private fun updateTextParagraphs(document: Document, namespace: String, edits: Map<String, String>) {
        val paragraphs = document.getElementsByTagNameNS(namespace, "p")
        edits.forEach { (id, replacement) ->
            val index = id.substringAfterLast('#').toIntOrNull() ?: return@forEach
            val paragraph = paragraphs.item(index) ?: return@forEach
            val texts = descendantElements(paragraph, namespace, "t")
            if (texts.isNotEmpty()) {
                texts.first().textContent = replacement
                texts.drop(1).forEach { it.textContent = "" }
            }
        }
    }

    private fun updateCells(document: Document, edits: Map<String, String>) {
        val cells = document.getElementsByTagNameNS(SHEET_NS, "c")
        edits.forEach { (id, replacement) ->
            val index = id.substringAfterLast('#').toIntOrNull() ?: return@forEach
            val cell = cells.item(index) as? Element ?: return@forEach
            listOf("f", "v", "is").forEach { local ->
                while (true) {
                    val node = firstChild(cell, SHEET_NS, local) ?: break
                    cell.removeChild(node)
                }
            }
            if (replacement.startsWith("=") && replacement.length > 1) {
                cell.removeAttribute("t")
                cell.appendChild(document.createElementNS(SHEET_NS, "f")).textContent = replacement.drop(1)
                cell.appendChild(document.createElementNS(SHEET_NS, "v")).textContent = ""
            } else {
                cell.setAttribute("t", "inlineStr")
                val inline = document.createElementNS(SHEET_NS, "is")
                inline.appendChild(document.createElementNS(SHEET_NS, "t")).textContent = replacement
                cell.appendChild(inline)
            }
        }
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val document = parse(zip.getInputStream(entry).readBytes())
        val items = document.getElementsByTagNameNS(SHEET_NS, "si")
        return (0 until items.length).map { index ->
            descendantElements(items.item(index), SHEET_NS, "t").joinToString("") { it.textContent }
        }
    }

    private fun parse(zip: ZipFile, name: String): Document {
        val entry = zip.getEntry(name) ?: error("文件缺少 $name")
        return zip.getInputStream(entry).use { parse(it.readBytes()) }
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(document: Document): ByteArray {
        val output = ByteArrayOutputStream()
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_STYLESHEET, "") }
        factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }

    private fun descendantElements(node: Node, namespace: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(current: Node) {
            if (current.nodeType == Node.ELEMENT_NODE && current.namespaceURI == namespace && current.localName == localName) {
                result += current as Element
            }
            var child = current.firstChild
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }
        visit(node)
        return result
    }

    private fun firstChild(parent: Element, namespace: String, localName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.namespaceURI == namespace && child.localName == localName) {
                return child as Element
            }
            child = child.nextSibling
        }
        return null
    }
}
