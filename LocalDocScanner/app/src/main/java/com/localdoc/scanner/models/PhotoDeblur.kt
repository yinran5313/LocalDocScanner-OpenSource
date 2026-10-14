package com.localdoc.scanner.models

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** MIT NAFNet, OpenCV Zoo models/deblurring_nafnet/nafnet.py Nafnet.infer.
 * The TLC export requires at least 384px; use 384px tiles with 64px context, not tiny crops.
 */
object PhotoDeblur {
    const val CORE = 256
    suspend fun restore(context: Context, source: Bitmap, strength: Float,
        resolve: (suspend (PhotoRestoration.Tile, () -> Bitmap) -> Bitmap)? = null
    ): Bitmap = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        require(strength.isFinite() && strength in 0f..1f)
        require(source.width.toLong()*source.height <= 4_000_000)
        ModelInference.requireImageMemory(context)
        val output = Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(output)
            ModelInference.session(ModelCatalog.file(context,"deblur","nafnet.onnx")).use { session ->
                val size=384; val pad=64; val n=size*size; val row=IntArray(source.width)
                for(y in 0 until source.height step CORE) for(x in 0 until source.width step CORE) {
                    currentCoroutineContext().ensureActive()
                    val tile=PhotoRestoration.Tile(x,y,minOf(CORE,source.width-x),minOf(CORE,source.height-y))
                    val infer: () -> Bitmap = {
                        val data=FloatArray(3*n)
                        for(ty in 0 until size) {
                            source.getPixels(row,0,source.width,0,(y+ty-pad).coerceIn(0,source.height-1),source.width,1)
                            for(tx in 0 until size) { val p=row[(x+tx-pad).coerceIn(0,source.width-1)]; val at=ty*size+tx
                                data[at]=Color.red(p)/255f; data[n+at]=Color.green(p)/255f; data[2*n+at]=Color.blue(p)/255f
                            }
                        }
                        ModelInference.floats(data,longArrayOf(1,3,384,384)).use { input -> session.run(mapOf("lq" to input)).use { results ->
                            val tensor=results[0] as OnnxTensor
                            require(tensor.info.shape.contentEquals(longArrayOf(1,3,384,384))) { "去模糊模型版本不匹配" }
                            val values=ModelInference.copy(tensor)
                            val pixels=IntArray(tile.width*tile.height) { i ->
                                val at=(i/tile.width+pad)*size+i%tile.width+pad
                                fun c(offset: Int): Int { val v=values[at+offset]; require(v.isFinite()) { "去模糊输出无效" }
                                    return ((data[at+offset]*(1-strength)+v.coerceIn(0f,1f)*strength)*255).roundToInt().coerceIn(0,255) }
                                Color.rgb(c(0),c(n),c(2*n))
                            }
                            Bitmap.createBitmap(pixels,tile.width,tile.height,Bitmap.Config.ARGB_8888)
                        } }
                    }
                    val fixed=resolve?.invoke(tile,infer) ?: infer()
                    try { require(fixed.width==tile.width && fixed.height==tile.height); canvas.drawBitmap(fixed,x.toFloat(),y.toFloat(),null) } finally { fixed.recycle() }
                }
            }
            if(source.hasAlpha()) canvas.drawBitmap(source,0f,0f,Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            output
        } catch(e: Throwable) { output.recycle(); throw e }
    } }
}
