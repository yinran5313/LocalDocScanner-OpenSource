package com.localdoc.scanner.models

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One optional model at a time; sessions and tensors close after use to bound handset memory. */
object ModelInference {
    internal val gate = Mutex()
    internal val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()
    internal fun requireImageMemory(context: Context) {
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        val memory=android.app.ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory)
        require(!memory.lowMemory && memory.availMem>=1024L*1024*1024) { "当前可用内存不足，请关闭不用的应用后重试；PDF可改用基础增强" }
    }
    internal fun session(file: java.io.File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
        env.createSession(file.absolutePath, options)
    }
    internal fun floats(data: FloatArray, shape: LongArray) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    internal fun longs(data: LongArray, shape: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
    internal fun copy(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer.duplicate()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
    internal fun pixels(bitmap: Bitmap, width: Int, height: Int, bgr: Boolean = false, mean: Float = 0f, std: Float = 1f): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val count = width * height; val rgb = IntArray(count); scaled.getPixels(rgb, 0, width, 0, 0, width, height)
            return FloatArray(count * 3).also { data -> rgb.forEachIndexed { i, color ->
                data[i] = ((if (bgr) Color.blue(color) else Color.red(color)) / 255f - mean) / std
                data[count + i] = (Color.green(color) / 255f - mean) / std
                data[count * 2 + i] = ((if (bgr) Color.red(color) else Color.blue(color)) / 255f - mean) / std
            } }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    suspend fun portrait(context: Context, bitmap: Bitmap, background: Int?): Bitmap = withContext(Dispatchers.Default) { gate.withLock {
        // MODNet's official processor uses RGB / 0.5 normalization and dimensions divisible by 32.
        val ratio = 512f / minOf(bitmap.width, bitmap.height)
        val cap = minOf(ratio, 1024f / maxOf(bitmap.width, bitmap.height))
        val w = ((bitmap.width * cap / 32).roundToInt().coerceAtLeast(1) * 32)
        val h = ((bitmap.height * cap / 32).roundToInt().coerceAtLeast(1) * 32)
        val alpha = session(ModelCatalog.file(context, "portrait", "model.onnx")).use { session ->
            floats(pixels(bitmap, w, h, mean = .5f, std = .5f), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
                session.run(mapOf("input" to input)).use { copy(it[0] as OnnxTensor) }
            }
        }
        require(alpha.size == w * h) { "人像模型输出不匹配" }
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(IntArray(alpha.size) { Color.argb((alpha[it].coerceIn(0f, 1f) * 255).roundToInt(), 255, 255, 255) }, 0, w, 0, 0, w, h)
        val full = Bitmap.createScaledBitmap(mask, bitmap.width, bitmap.height, true)
        try {
            val original = IntArray(bitmap.width * bitmap.height); val masks = IntArray(original.size)
            bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            full.getPixels(masks, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            currentCoroutineContextCheck()
            val result = IntArray(original.size) { i ->
                val a = Color.alpha(masks[i]); val p = original[i]
                if (background == null) Color.argb(a, Color.red(p), Color.green(p), Color.blue(p))
                else { fun blend(x: Int, y: Int) = (x * a + y * (255 - a) + 127) / 255
                    Color.rgb(blend(Color.red(p), Color.red(background)), blend(Color.green(p), Color.green(background)), blend(Color.blue(p), Color.blue(background))) }
            }
            Bitmap.createBitmap(result, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        } finally { if (full !== mask) full.recycle(); mask.recycle() }
    } }

    suspend fun unwarp(context: Context, bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) { gate.withLock {
        // PaddleX WarpRunnerPredictor / DocTrPostProcess: BGR input [0,1], RGB CHW output.
        val data = pixels(bitmap, bitmap.width, bitmap.height, bgr = true)
        session(ModelCatalog.file(context, "unwarp", "inference.onnx")).use { session ->
            floats(data, longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong())).use { input ->
                session.run(mapOf("image" to input)).use { result ->
                    val tensor = result[0] as OnnxTensor; val shape = tensor.info.shape
                    require(shape.size == 4 && shape[1] == 3L)
                    val h = shape[2].toInt(); val w = shape[3].toInt(); val n = w * h
                    require(n in 1..12_000_000) { "展平结果过大" }
                    val values = copy(tensor)
                    Bitmap.createBitmap(IntArray(n) { i ->
                        fun channel(offset: Int) = (values[offset + i].coerceIn(0f, 1f) * 255).roundToInt()
                        Color.rgb(channel(0), channel(n), channel(2 * n))
                    }, w, h, Bitmap.Config.ARGB_8888)
                }
            }
        }
    } }
    private suspend fun currentCoroutineContextCheck() = kotlinx.coroutines.currentCoroutineContext().ensureActive()
}
