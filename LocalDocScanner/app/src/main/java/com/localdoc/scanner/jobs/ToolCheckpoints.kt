package com.localdoc.scanner.jobs

import com.google.gson.Gson
import com.localdoc.scanner.util.AtomicFiles
import java.io.File
import java.security.MessageDigest

/** One durable receipt per unit. Completed units survive failure of the final export. */
class ToolCheckpoints(val directory: File, private val gson: Gson) {
    private fun receipt(key: String) = File(directory, "units/${digest(key.toByteArray())}.json")
    fun load(key: String): ToolUnitReceipt? = runCatching {
        val saved = gson.fromJson(receipt(key).readText(), ToolUnitReceipt::class.java)
        saved.takeIf { it.key == key && it.files.all { file ->
            File(file.path).isFile && hash(File(file.path)) == file.hash
        } }
    }.getOrNull()
    fun save(key: String, payload: String, files: List<File>) {
        require(files.all { it.isFile && it.length() > 0 }) { "输出缺失或为空，无法提交完成记录" }
        val saved = ToolUnitReceipt(key, payload, files.map { ToolFileReceipt(it.absolutePath, hash(it)) })
        AtomicFiles.text(receipt(key), gson.toJson(saved))
    }
    companion object {
        fun hash(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
