package com.localdoc.scanner.office

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OpenXmlEditorTest {
    @Test
    fun editsWordParagraphAndPreservesOtherEntries() {
        val input = zip(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
            "word/media/image1.png" to byteArrayOf(1, 7, 9, 3)
        )
        val document = OpenXmlEditor.read(input)
        assertEquals(listOf("第一段", "第二段"), document.units.map { it.text })
        val output = File(input.parentFile, "saved.docx")
        OpenXmlEditor.save(input, output, mapOf(document.units[1].id to "修改后的第二段"))
        assertEquals(listOf("第一段", "修改后的第二段"), OpenXmlEditor.read(output).units.map { it.text })
        ZipFile(output).use { zip ->
            assertArrayEquals(byteArrayOf(1, 7, 9, 3), zip.getInputStream(zip.getEntry("word/media/image1.png")).readBytes())
        }
    }

    @Test
    fun editsSpreadsheetCellAndFormula() {
        val input = zip(
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>旧名称</t></si></sst>""".toByteArray(),
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="B1"><v>8</v></c></row></sheetData></worksheet>""".toByteArray()
        )
        val document = OpenXmlEditor.read(input)
        val output = File(input.parentFile, "saved.xlsx")
        OpenXmlEditor.save(
            input,
            output,
            mapOf(document.units.first { it.label == "A1" }.id to "新名称", document.units.first { it.label == "B1" }.id to "=SUM(2,3)")
        )
        val reread = OpenXmlEditor.read(output)
        assertEquals("新名称", reread.units.first { it.label == "A1" }.text)
        assertEquals("=SUM(2,3)", reread.units.first { it.label == "B1" }.text)
    }

    @Test
    fun editsPresentationTextWithoutDroppingSlideAsset() {
        val input = zip(
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><a:p><a:r><a:t>标题</a:t></a:r></a:p></p:cSld></p:sld>""".toByteArray(),
            "ppt/media/image1.jpeg" to byteArrayOf(4, 2, 4, 2)
        )
        val document = OpenXmlEditor.read(input)
        val output = File(input.parentFile, "saved.pptx")
        OpenXmlEditor.save(input, output, mapOf(document.units.single().id to "新标题"))
        assertEquals("新标题", OpenXmlEditor.read(output).units.single().text)
        ZipFile(output).use { zip ->
            assertTrue(zip.getEntry("ppt/media/image1.jpeg") != null)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val dir = Files.createTempDirectory("openxml-test").toFile()
        val file = File(dir, "input.${when {
            entries.any { it.first.startsWith("word/") } -> "docx"
            entries.any { it.first.startsWith("xl/") } -> "xlsx"
            else -> "pptx"
        }}")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
