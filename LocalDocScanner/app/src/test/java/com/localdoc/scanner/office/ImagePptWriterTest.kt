package com.localdoc.scanner.office

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class ImagePptWriterTest {
    @Test fun allSlidePartsAndRelationshipsRemainValidForMixedAspectPhotos() {
        val template = File("../office-engine/src/main/assets/templates/untitled.pptx")
        val output = File.createTempFile("pictures-", ".pptx")
        val picture = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")
        try {
            ImagePptWriter.write(template.inputStream(), listOf(PptPicture(picture, 1000, 500), PptPicture(picture, 500, 1000)), output)
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(names.size, names.distinct().size)
                assertTrue(names.containsAll(listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/media/photo1.png", "ppt/media/photo2.png")))
                names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
                    zip.getInputStream(zip.getEntry(name)).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
                }
                val relations = zip.getInputStream(zip.getEntry("ppt/_rels/presentation.xml.rels")).bufferedReader().readText()
                assertTrue(relations.contains("Target=\"slides/slide2.xml\"")); assertFalse(relations.contains("Id=\"rId3\""))
                val second = zip.getInputStream(zip.getEntry("ppt/slides/slide2.xml")).bufferedReader().readText()
                assertTrue(second.contains("r:embed=\"picture\"")); assertTrue(second.contains("cy=\"5670550\""))
            }
        } finally { output.delete() }
    }
}
