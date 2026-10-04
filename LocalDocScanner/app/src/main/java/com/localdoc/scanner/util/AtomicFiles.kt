package com.localdoc.scanner.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Commit a new file only after its content is completely written and synced. */
object AtomicFiles {
    inline fun write(target: File, block: (File) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            block(part)
            require(part.isFile) { "没有生成文件：${target.name}" }
            FileOutputStream(part, true).use { it.fd.sync() }
            try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { part.delete() }
    }
    fun text(target: File, content: String) = write(target) { it.writeText(content, Charsets.UTF_8) }
    fun copy(source: File, target: File) = write(target) { source.copyTo(it) }
}
