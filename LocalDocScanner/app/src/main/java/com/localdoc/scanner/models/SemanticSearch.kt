package com.localdoc.scanner.models

import android.content.Context
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import com.localdoc.scanner.data.SearchHit
import com.localdoc.scanner.data.FullTextIndex
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

object SemanticSearch {
    suspend fun search(context: Context, query: String): List<Pair<SearchHit, Float>> = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        val tokenizer = WordPiece(ModelCatalog.file(context, "semantic", "vocab.txt").readLines())
        val pack = ModelCatalog.get(context, "semantic")
        val cache = File(context.cacheDir, "semantic-${pack.version}").apply { mkdirs() }
        prune(context)
        var visited = 0
        ModelInference.session(ModelCatalog.file(context, "semantic", "model_quantized.onnx")).use { session ->
            fun embedding(text: String): FloatArray {
                val ids = tokenizer.encode(text); val shape = longArrayOf(1, ids.size.toLong())
                val inputs = mapOf("input_ids" to ModelInference.longs(ids, shape), "attention_mask" to ModelInference.longs(LongArray(ids.size) { 1 }, shape),
                    "token_type_ids" to ModelInference.longs(LongArray(ids.size), shape))
                try { return session.run(inputs).use { result ->
                    val all = ModelInference.copy(result[0] as OnnxTensor)
                    require(all.size == ids.size * 512)
                    val cls = all.copyOfRange(0, 512); val norm = sqrt(cls.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-8f)
                    FloatArray(cls.size) { cls[it] / norm }
                } } finally { inputs.values.forEach { it.close() } }
            }
            val q = embedding("为这个句子生成表示以用于检索相关文章：$query")
            val records = FullTextIndex(context).use { it.allContent() }
            val scored = mutableListOf<Pair<SearchHit, Float>>()
            records.forEach { (hit, body) ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (body.isBlank()) return@forEach
                var best = -1f; var bestChunk = ""
                val chunks = body.take(100_000).chunked(550).take(180)
                chunks.forEachIndexed { index, chunk ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val text = hit.name + "\n" + chunk
                    val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                    val file = File(cache, hash)
                    if (++visited % 128 == 0) prune(context)
                    val vector = if (file.length() == 512L * 4) { val b = ByteBuffer.wrap(file.readBytes()); FloatArray(512) { b.float } }
                    else embedding(text).also { v -> val b = ByteBuffer.allocate(v.size * 4); v.forEach(b::putFloat); com.localdoc.scanner.util.AtomicFiles.write(file) { it.writeBytes(b.array()) } }
                    file.setLastModified(System.currentTimeMillis())
                    val score = q.indices.sumOf { (q[it] * vector[it]).toDouble() }.toFloat()
                    if (score > best) { best = score; bestChunk = chunks[index].take(240) }
                }
                scored += hit.copy(snippet = bestChunk) to best
            }
            scored.sortedByDescending { it.second }.take(30)
        }
    } }
    private fun prune(context: Context) {
        val cacheRoot = context.cacheDir.canonicalFile
        val files = cacheRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("semantic-[a-f0-9]{12}")) }.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}")) && it.canonicalFile.parentFile == dir.canonicalFile }
        }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }; var count = files.size
        for (file in files) {
            if (bytes <= 32L*1024*1024 && count <= 16_000) break
            val size = file.length(); if (file.delete()) { bytes -= size; count-- }
        }
    }

}
