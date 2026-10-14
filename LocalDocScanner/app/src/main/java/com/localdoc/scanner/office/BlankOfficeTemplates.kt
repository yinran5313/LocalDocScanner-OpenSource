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
                        } else input.copyTo(output)
                        output.closeEntry(); input.closeEntry()
                    }
                }
            }
        }
    }
}
