package com.localdoc.scanner.models

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.localdoc.scanner.tokenizer.SentencePieces
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Marian quantized encoder/merged decoder, using the model's source SentencePiece and vocabulary. */
object LocalTranslation {
    suspend fun translate(context: Context, text: String, chineseToEnglish: Boolean): String = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        require(text.length <= 8000) { "一次翻译限8000字符，请分段处理" }
        val id = if (chineseToEnglish) "translate-zh-en" else "translate-en-zh"
        val vocabJson = JSONObject(ModelCatalog.file(context, id, "vocab.json").readText())
        val vocab = vocabJson.keys().asSequence().associateWith { vocabJson.getLong(it) }
        val reverse = vocab.entries.associate { it.value to it.key }
        val config = JSONObject(ModelCatalog.file(context, id, "config.json").readText())
        val eos = config.getLong("eos_token_id"); val pad = config.getLong("pad_token_id")
        val unknown = vocab["<unk>"] ?: 1L
        SentencePieces(ModelCatalog.file(context, id, "source.spm").absolutePath).use { tokenizer ->
            ModelInference.session(ModelCatalog.file(context, id, "encoder_model_quantized.onnx")).use { encoder ->
                ModelInference.session(ModelCatalog.file(context, id, "decoder_model_merged_quantized.onnx")).use { decoder ->
                    val lines = text.lines().map { line ->
                        if (line.isBlank()) "" else {
                            // Segments remain bounded even for source with no punctuation; no silent source truncation.
                            val pieces = tokenizer.encode(line)
                            pieces.chunked(220).map { segment ->
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val ids = (segment.map { vocab[it] ?: unknown } + eos).toLongArray()
                                val mask = ModelInference.longs(LongArray(ids.size) { 1 }, longArrayOf(1, ids.size.toLong()))
                                val source = ModelInference.longs(ids, longArrayOf(1, ids.size.toLong()))
                                val caches = mutableMapOf<String, OnnxTensor>()
                                var hidden: OnnxTensor? = null
                                try {
                                    encoder.run(mapOf("input_ids" to source, "attention_mask" to mask)).use { result ->
                                        val tensor = result[0] as OnnxTensor
                                        hidden = ModelInference.floats(ModelInference.copy(tensor), tensor.info.shape)
                                    }
                                    decoder.inputNames.filter { it.startsWith("past_key_values.") }.forEach { name ->
                                        caches[name] = ModelInference.floats(FloatArray(0), longArrayOf(1, 8, 0, 64))
                                    }
                                    var token = config.optLong("decoder_start_token_id", pad); var cache = false
                                    val output = mutableListOf<Long>(); var ended = false
                                    for (step in 0 until 384) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        val current = ModelInference.longs(longArrayOf(token), longArrayOf(1, 1))
                                        val branch = OnnxTensor.createTensor(ModelInference.env, booleanArrayOf(cache))
                                        val inputs = mapOf("input_ids" to current, "encoder_attention_mask" to mask,
                                            "encoder_hidden_states" to hidden!!, "use_cache_branch" to branch) + caches
                                        val next = mutableMapOf<String, OnnxTensor>()
                                        try {
                                            decoder.run(inputs).use { result ->
                                                val logits = ModelInference.copy(result.get("logits").get() as OnnxTensor)
                                                require(logits.size >= 65001)
                                                var best = -1; var bestValue = Float.NEGATIVE_INFINITY
                                                for (index in logits.indices) if (index.toLong() != pad && (step > 0 || index.toLong() != eos) && logits[index] > bestValue) {
                                                    best = index; bestValue = logits[index]
                                                }
                                                require(best >= 0 && bestValue.isFinite()) { "翻译模型输出异常" }; token = best.toLong()
                                                if (token != eos) decoder.outputNames.filter { it.startsWith("present.") }.forEach { name ->
                                                    val inputName = name.replace("present.", "past_key_values.")
                                                    val tensor = result.get(name).get() as OnnxTensor
                                                    // Cached encoder outputs can be empty on subsequent branch; retain established encoder KV.
                                                    if (cache && name.contains(".encoder.")) next[inputName] = caches.remove(inputName)!!
                                                    else next[inputName] = ModelInference.floats(ModelInference.copy(tensor), tensor.info.shape)
                                                }
                                            }
                                        } catch (e: Exception) { next.values.forEach { it.close() }; throw e }
                                        finally { current.close(); branch.close() }
                                        caches.values.forEach { it.close() }; caches.clear(); caches.putAll(next)
                                        if (token == eos) { ended = true; break }
                                        output += token; cache = true
                                    }
                                    require(ended) { "此段翻译超过输出长度，请缩短段落后重试" }
                                    output.joinToString("") { reverse[it].orEmpty() }.replace("▁", " ").trim()
                                } finally { caches.values.forEach { it.close() }; hidden?.close(); source.close(); mask.close() }
                            }.joinToString(if (chineseToEnglish) " " else "")
                        }
                    }
                    lines.joinToString("\n")
                }
            }
        }
    } }
}
