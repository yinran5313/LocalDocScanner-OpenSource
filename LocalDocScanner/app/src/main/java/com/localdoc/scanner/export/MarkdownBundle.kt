package com.localdoc.scanner.export

import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Relative image references remain portable when the complete bundle is extracted. */
object MarkdownBundle {
    suspend fun write(files: List<File>, output: File) {
        require(files.isNotEmpty() && files.size <= 3000) { "附件过多，请分组导出" }
        require(files.all { it.isFile } && files.map { it.name.lowercase() }.distinct().size == files.size) { "附件缺失或名称重复" }
        require(files.sumOf { it.length() } <= 512L*1024*1024) { "附件超过512MB，请分组导出" }
        val job = currentCoroutineContext()
        AtomicFiles.write(output) { staged -> ZipOutputStream(staged.outputStream().buffered()).use { zip ->
            val buffer=ByteArray(64*1024)
            files.forEach { file ->
                job.ensureActive()
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().buffered().use { input -> while(true) {
                    job.ensureActive(); val n=input.read(buffer); if(n<0)break; zip.write(buffer,0,n)
                } }
                zip.closeEntry()
            }
        } }
    }
}
