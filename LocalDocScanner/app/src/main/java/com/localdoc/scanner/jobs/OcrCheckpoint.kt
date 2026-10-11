package com.localdoc.scanner.jobs

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class OcrPageCheckpoint(val id: String, val hash: String, val recipe: String,
    val done: Boolean = false, val savedAt: Long = 0, val error: String = "")
data class OcrCheckpoint(val id: String, val docId: String, val precise: Boolean,
    val pages: List<OcrPageCheckpoint>, val createdAt: Long = System.currentTimeMillis(), val language: String? = "AUTO")

/** Atomic journal containing input hashes and receipts, never page images. */
object OcrCheckpointStore {
    private val gson = Gson()
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun file(root: File, id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "无效任务编号" }
        root.mkdirs(); return File(root, "$id.json")
    }
    fun load(root: File, id: String): OcrCheckpoint = gson.fromJson(file(root, id).readText(), OcrCheckpoint::class.java)
        .also { require(it.id == id && it.pages.size <= 10000) { "任务清单损坏" } }
    fun save(root: File, value: OcrCheckpoint) {
        val target = file(root, value.id)
        val staged = File(root, "${value.id}.${UUID.randomUUID()}.part")
        try {
            java.io.FileOutputStream(staged).use { it.write(gson.toJson(value).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        } finally { staged.delete() }
    }
    fun canReuse(saved: OcrPageCheckpoint, hash: String, recipe: String, ocrUpdatedAt: Long): Boolean =
        saved.done && saved.hash == hash && saved.recipe == recipe && saved.savedAt > 0 && saved.savedAt == ocrUpdatedAt
}
