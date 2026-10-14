package com.localdoc.scanner.office

import com.localdoc.scanner.util.AtomicFiles
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PptPicture(val png: ByteArray, val width: Int, val height: Int, val file: File? = null)
/** Package photographed slides as pictures using the existing blank Office template. */
object ImagePptWriter {
    fun write(template: InputStream, pictures: List<PptPicture>, target: File) {
        require(pictures.isNotEmpty() && pictures.size <= 500 && pictures.all { it.width > 0 && it.height > 0 && (it.png.isNotEmpty() || it.file?.isFile == true) })
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(template).use { zip -> while (true) { val e = zip.nextEntry ?: break; parts[e.name] = zip.readBytes(); zip.closeEntry() } }
        val presentation = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8)
        val size = Regex("<p:sldSz cx=\"([0-9]+)\" cy=\"([0-9]+)\"").find(presentation) ?: error("模板页面尺寸缺失")
        val pageW = size.groupValues[1].toLong(); val pageH = size.groupValues[2].toLong()
        val ids = pictures.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"pictureSlide${it + 1}\"/>" }
        parts["ppt/presentation.xml"] = presentation.replace(Regex("<p:sldIdLst>.*?</p:sldIdLst>"), "<p:sldIdLst>$ids</p:sldIdLst>").toByteArray()
        var relationships = parts.getValue("ppt/_rels/presentation.xml.rels").toString(Charsets.UTF_8)
        relationships = relationships.replace(Regex("<Relationship[^>]*Type=\"[^\"]*/slide\"[^>]*/>"), "")
        relationships = relationships.replace("</Relationships>", pictures.indices.joinToString("") { "<Relationship Id=\"pictureSlide${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } + "</Relationships>")
        parts["ppt/_rels/presentation.xml.rels"] = relationships.toByteArray()
        var types = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8).replace(Regex("<Override PartName=\"/ppt/slides/[^\"]*\"[^>]*/>"), "")
        types = types.replace("</Types>", pictures.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } + "</Types>")
        parts["[Content_Types].xml"] = types.toByteArray()
        parts.keys.filter { it.startsWith("ppt/slides/") }.toList().forEach(parts::remove)
        AtomicFiles.write(target) { staged -> ZipOutputStream(staged.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            parts.forEach { (name, bytes) -> put(name, bytes) }
            pictures.forEachIndexed { index, picture ->
                val n = index + 1; val scale = minOf(pageW.toDouble() / picture.width, pageH.toDouble() / picture.height)
                val w = (picture.width * scale).toLong(); val h = (picture.height * scale).toLong()
                val slide = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr><p:pic><p:nvPicPr><p:cNvPr id="2" name="照片$n"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="picture"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="${(pageW-w)/2}" y="${(pageH-h)/2}"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
                put("ppt/slides/slide$n.xml", slide.toByteArray())
                put("ppt/slides/_rels/slide$n.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="layout" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="picture" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/photo$n.png"/></Relationships>""".toByteArray())
                if (picture.file == null) put("ppt/media/photo$n.png", picture.png)
                else { zip.putNextEntry(ZipEntry("ppt/media/photo$n.png")); picture.file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        } }
    }
}
