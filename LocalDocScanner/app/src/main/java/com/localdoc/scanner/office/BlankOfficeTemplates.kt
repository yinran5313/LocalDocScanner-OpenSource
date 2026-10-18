package com.localdoc.scanner.office

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.localdoc.scanner.util.AtomicFiles

/** Adapt only the newly created work copy; the fixed Office runtime assets stay byte-identical. */
object BlankOfficeTemplates {
    fun create(context: Context, extension: String, target: File) {
        require(extension in setOf("docx", "xlsx", "pptx"))
        AtomicFiles.write(target) { part ->
            ZipInputStream(context.assets.open("templates/untitled.$extension")).use { input ->
                ZipOutputStream(part.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "word/document.xml") {
                            val text = input.readBytes().toString(Charsets.UTF_8).replace("w:w=\"12240\" w:h=\"15840\"", "w:w=\"11906\" w:h=\"16838\"")
                            output.write(text.toByteArray(Charsets.UTF_8))
                        } else if (entry.name == "xl/workbook.xml") {
                            output.write(input.readBytes().toString(Charsets.UTF_8).replace("name=\"Sheet1\"", "name=\"工作表1\"").toByteArray(Charsets.UTF_8))
                        } else if (extension == "pptx" && entry.name.startsWith("ppt/") && entry.name.endsWith(".xml")) {
                            output.write(chinesePlaceholders(input.readBytes().toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                        } else input.copyTo(output)
                        output.closeEntry(); input.closeEntry()
                    }
                }
            }
        }
    }
    internal fun chinesePlaceholders(xml: String): String {
        val labels=mapOf("Click to edit the title text format" to "点击添加标题", "Click to edit the outline text format" to "点击添加正文",
            "Second Outline Level" to "第二级正文", "Third Outline Level" to "第三级正文", "Fourth Outline Level" to "第四级正文",
            "Fifth Outline Level" to "第五级正文", "Sixth Outline Level" to "第六级正文", "Seventh Outline Level" to "第七级正文",
            "Footer" to "页脚", "&lt;date/time&gt;" to "日期", "&lt;footer&gt;" to "页脚", "&lt;number&gt;" to "页码")
        return labels.entries.fold(xml) { value,(english,chinese) -> value.replace("<a:t>$english</a:t>","<a:t>$chinese</a:t>") }
    }
}
