package com.localdoc.scanner.models

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.math.roundToInt

/** RapidAI/RapidLaTeXOCR PreProcess, LaTeXOCR.loop_image_resizer, EncoderDecoder (MIT).
 *  Port: bounded image/greedy decoding, scoped tensors, no cloud service. */
object FormulaRecognition {
    private fun pad(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val transparent = pixels.any { Color.alpha(it) != Color.alpha(pixels[0]) }
        val gray = IntArray(pixels.size) { i -> val p = pixels[i]; if (transparent) 255-Color.alpha(p) else ((Color.red(p)*.299+Color.green(p)*.587+Color.blue(p)*.114)).roundToInt() }
        val low = gray.minOrNull() ?: 0; val high = gray.maxOrNull() ?: 0
        require(high-low > 10) { "没有清晰的公式笔迹，请裁成单条公式并重拍" }
        for (i in gray.indices) gray[i] = (gray[i]-low)*255/(high-low)
        if (gray.average() <= 128) for (i in gray.indices) gray[i] = 255-gray[i]
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (gray[y*bitmap.width+x] < 128) { left = minOf(left,x); top = minOf(top,y); right = maxOf(right,x); bottom = maxOf(bottom,y) }
        require(right >= left && bottom >= top) { "未找到公式笔迹" }
        val w = right-left+1; val h = bottom-top+1
        val region = Bitmap.createBitmap(IntArray(w*h) { i -> val value = gray[(i/w+top)*bitmap.width+i%w+left]; Color.rgb(value,value,value) },w,h,Bitmap.Config.ARGB_8888)
        try {
            val ratio = minOf(1f,672f/w,192f/h)
            val rw = (w*ratio).toInt().coerceAtLeast(1); val rh = (h*ratio).toInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(((rw+31)/32*32).coerceAtLeast(32),((rh+31)/32*32).coerceAtLeast(32),Bitmap.Config.ARGB_8888)
            Canvas(result).apply { drawColor(Color.WHITE); drawBitmap(region,null,Rect(0,0,rw,rh),Paint(Paint.FILTER_BITMAP_FLAG)) }
            return result
        } finally { region.recycle() }
    }
    private fun input(bitmap: Bitmap): OnnxTensor {
        val p = IntArray(bitmap.width*bitmap.height); bitmap.getPixels(p,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val values = FloatArray(p.size) { (Color.red(p[it])/255f-.7931f)/.1738f }
        return ModelInference.floats(values,longArrayOf(1,1,bitmap.height.toLong(),bitmap.width.toLong()))
    }
    suspend fun recognize(context: Context, source: Bitmap): String = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        val original = pad(source); var prepared = original
        try {
            ModelInference.session(ModelCatalog.file(context,"formula","image_resizer.onnx")).use { resizer ->
                var width = original.width; var height = original.height; var ratio = 1f
                for (attempt in 0 until 10) {
                    currentCoroutineContext().ensureActive()
                    height = (height*ratio).roundToInt().coerceIn(1,192)
                    val resized = Bitmap.createScaledBitmap(original,width.coerceIn(1,672),height,true)
                    val next = try { pad(resized) } finally { if (resized !== original) resized.recycle() }
                    if (prepared !== original) prepared.recycle(); prepared = next
                    val predicted = input(prepared).use { x -> resizer.run(mapOf("input" to x)).use { r ->
                        val scores = ModelInference.copy(r[0] as OnnxTensor); ((scores.indices.maxByOrNull { scores[it] } ?: 0)+1)*32
                    } }.coerceIn(32,672)
                    if (predicted == prepared.width) break
                    width = predicted; ratio = predicted.toFloat()/prepared.width
                }
            }
            val vocabulary = JSONObject(ModelCatalog.file(context,"formula","tokenizer.json").readText()).getJSONObject("model").getJSONObject("vocab")
            val tokens = vocabulary.keys().asSequence().associateBy { vocabulary.getInt(it) }
            ModelInference.session(ModelCatalog.file(context,"formula","encoder.onnx")).use { encoder ->
                input(prepared).use { image -> encoder.run(mapOf("input" to image)).use { encoded ->
                    val contextTensor = encoded[0] as OnnxTensor
                    ModelInference.session(ModelCatalog.file(context,"formula","decoder.onnx")).use { decoder ->
                        val ids = mutableListOf(1L); var eos = false
                        for (i in 0 until 512) {
                            currentCoroutineContext().ensureActive()
                            ModelInference.longs(ids.toLongArray(),longArrayOf(1,ids.size.toLong())).use { x ->
                                OnnxTensor.createTensor(ModelInference.env,arrayOf(BooleanArray(ids.size) { true })).use { mask ->
                                    val token = decoder.run(mapOf("x" to x,"mask" to mask,"context" to contextTensor)).use { r ->
                                        val scores = ModelInference.copy(r[0] as OnnxTensor); val offset = scores.size-8000
                                        var best = 0; for (j in 1 until 8000) if (scores[offset+j] > scores[offset+best]) best = j
                                        best.toLong()
                                    }
                                    ids += token; if (token == 2L) eos = true
                                }
                            }
                            if (eos) break
                        }
                        require(eos) { "公式过长或识别未结束，请裁成单条公式再试" }
                        ids.drop(1).filter { it > 2 }.joinToString("") { tokens[it.toInt()].orEmpty() }.replace("Ġ"," ").trim().also { require(it.isNotBlank()) { "没有识别到公式" } }
                    }
                } }
            }
        } finally { if (prepared !== original) prepared.recycle(); original.recycle() }
    } }
}
