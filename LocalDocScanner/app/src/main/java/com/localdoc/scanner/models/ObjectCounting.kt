package com.localdoc.scanner.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp
import kotlin.math.roundToInt

data class CountedObject(val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
object ObjectCounting {
    val labels = listOf("人", "汽车", "瓶子", "杯子", "书")
    private val ids = intArrayOf(1, 3, 44, 47, 84)
    suspend fun detect(context: Context, bitmap: Bitmap, category: Int): List<CountedObject> = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        require(category in labels.indices)
        val scale = minOf(512f / minOf(bitmap.width, bitmap.height), 1333f / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1); val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val rgb = ModelInference.pixels(bitmap, w, h)
        val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        for (channel in 0..2) for (i in 0 until w * h) { val index = channel * w * h + i; rgb[index] = (rgb[index] - mean[channel]) / std[channel] }
        ModelInference.session(ModelCatalog.file(context, "objects", "model_quantized.onnx")).use { session ->
            ModelInference.floats(rgb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
                session.run(mapOf("pixel_values" to input)).use { result ->
                    val tensor = result.get("logits").get() as OnnxTensor; val dimensions = tensor.info.shape
                    val classes = dimensions.last().toInt(); val count = dimensions[1].toInt(); val logits = ModelInference.copy(tensor)
                    val bounds = ModelInference.copy(result.get("pred_boxes").get() as OnnxTensor)
                    require(count in 1..1000 && classes > 90 && bounds.size == count * 4)
                    buildList { for (objectIndex in 0 until count) {
                        val base = objectIndex * classes; val best = (0 until classes - 1).maxBy { logits[base + it] }
                        if (best != ids[category]) continue
                        val max = (0 until classes).maxOf { logits[base + it] }
                        val sum = (0 until classes).sumOf { exp((logits[base + it] - max).toDouble()) }
                        val score = (exp((logits[base + best] - max).toDouble()) / sum).toFloat()
                        if (!score.isFinite() || score < .5f) continue
                        val b = objectIndex * 4; val cx = bounds[b]; val cy = bounds[b + 1]; val bw = bounds[b + 2]; val bh = bounds[b + 3]
                        val box = CountedObject(score, (cx - bw / 2).coerceIn(0f, 1f), (cy - bh / 2).coerceIn(0f, 1f), (cx + bw / 2).coerceIn(0f, 1f), (cy + bh / 2).coerceIn(0f, 1f))
                        if (listOf(box.left, box.top, box.right, box.bottom).all(Float::isFinite) && box.left < box.right && box.top < box.bottom) add(box)
                    } }
                }
            }
        }
    } }
}
