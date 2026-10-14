package com.localdoc.scanner.models

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** RealESRGANer.tile_process, BSD-3-Clause. Fixed 224 export; 32px padding, 160px cores. */
object PhotoRestoration {
    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)
    // Resolver returns an owned bitmap. It may load a verified checkpoint or run/save inference.
    suspend fun restore(context: Context, source: Bitmap, scale: Int,
        resolve: (suspend (Tile, () -> Bitmap) -> Bitmap)? = null
    ): Bitmap = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        ModelInference.requireImageMemory(context)
        RestorationGeometry.requireOutput(source.width, source.height, scale)
        val output = Bitmap.createBitmap(source.width*scale,source.height*scale,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            ModelInference.session(ModelCatalog.file(context,"restore","realesrgan-x4.onnx")).use { session ->
                val size = 224; val padding = 32; val core = size-2*padding; val n = size*size
                val sourceRow = IntArray(source.width)
                for (y in 0 until source.height step core) for (x in 0 until source.width step core) {
                    currentCoroutineContext().ensureActive()
                    val tileInfo = Tile(x, y, minOf(core, source.width-x), minOf(core, source.height-y))
                    val infer: () -> Bitmap = {
                    val data = FloatArray(n*3)
                    for (ty in 0 until size) {
                        val sy = (y+ty-padding).coerceIn(0,source.height-1)
                        source.getPixels(sourceRow,0,source.width,0,sy,source.width,1)
                        for (tx in 0 until size) {
                            val pixel = sourceRow[(x+tx-padding).coerceIn(0,source.width-1)]; val i = ty*size+tx
                            data[i] = Color.red(pixel)/255f; data[n+i] = Color.green(pixel)/255f; data[2*n+i] = Color.blue(pixel)/255f
                        }
                    }
                    ModelInference.floats(data,longArrayOf(1,3,size.toLong(),size.toLong())).use { input ->
                        session.run(mapOf("input.1" to input)).use { result ->
                            val tensor = result[0] as OnnxTensor; require(tensor.info.shape.contentEquals(longArrayOf(1,3,896,896))) { "修复模型版本不匹配" }
                            val values = ModelInference.copy(tensor); val stride = 896; val channel = stride*stride
                            val w = minOf(core,source.width-x)*4; val h = minOf(core,source.height-y)*4
                            val pixels = IntArray(w*h) { i ->
                                val at = (i/w+padding*4)*stride+i%w+padding*4
                                fun c(offset: Int) = (values[at+offset].coerceIn(0f,1f)*255).roundToInt()
                                Color.rgb(c(0),c(channel),c(2*channel))
                            }
                            val raw = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                            if (scale == 4) raw else try { Bitmap.createScaledBitmap(raw,w/2,h/2,true) } finally { raw.recycle() }
                        }
                    }
                    }
                    val tile = resolve?.invoke(tileInfo,infer) ?: infer()
                    try {
                        require(tile.width == tileInfo.width*scale && tile.height == tileInfo.height*scale) { "图块缓存尺寸不匹配，请重新提交" }
                        canvas.drawBitmap(tile, (x*scale).toFloat(), (y*scale).toFloat(), paint)
                    } finally { tile.recycle() }
                }
            }
            if (source.hasAlpha()) {
                val alphaPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                canvas.drawBitmap(source,null,Rect(0,0,output.width,output.height),alphaPaint)
            }
            output
        } catch (e: Throwable) { output.recycle(); throw e }
    } }
}
