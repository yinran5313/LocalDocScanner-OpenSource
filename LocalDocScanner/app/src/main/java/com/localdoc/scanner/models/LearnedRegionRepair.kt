package com.localdoc.scanner.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import com.localdoc.scanner.cv.RegionRepair
import com.localdoc.scanner.pdf.PdfInkPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** LaMa (Apache-2.0), Carve fixed FP32 ONNX export: RGB [0,1], binary mask; RGB [0,255] output.
 * Source: Carve-Photos/lama/bin/predict.py main; models replace only explicitly selected pixels.
 */
object LearnedRegionRepair {
    suspend fun repair(context: Context, source: Bitmap, strokes: List<List<PdfInkPoint>>, brush: Float): Bitmap =
        withContext(Dispatchers.Default) { ModelInference.gate.withLock {
            ModelInference.requireImageMemory(context)
            val mask=RegionRepair.selectionMask(source.width,source.height,strokes,brush)
            try {
                val bounds=Imgproc.boundingRect(mask)
                val margin=maxOf(32,(maxOf(bounds.width,bounds.height)*.2f).roundToInt())
                val left=(bounds.x-margin).coerceAtLeast(0); val top=(bounds.y-margin).coerceAtLeast(0)
                val right=(bounds.x+bounds.width+margin).coerceAtMost(source.width); val bottom=(bounds.y+bounds.height+margin).coerceAtMost(source.height)
                val w=right-left; val h=bottom-top
                val (fitW,fitH)=RestorationGeometry.fit(w,h,512)
                val ox=(512-fitW)/2; val oy=(512-fitH)/2; val n=512*512
                val selected=ByteArray(source.width*source.height); mask.get(0,0,selected)
                val original=IntArray(w*h); source.getPixels(original,0,w,left,top,w,h)
                val rgb=FloatArray(3*n); val inputMask=FloatArray(n)
                for(y in 0 until 512) for(x in 0 until 512) {
                    val sx=(((x-ox+.5f)/fitW*w).toInt()).coerceIn(0,w-1)
                    val sy=(((y-oy+.5f)/fitH*h).toInt()).coerceIn(0,h-1)
                    val at=y*512+x; val pixel=original[sy*w+sx]
                    rgb[at]=Color.red(pixel)/255f; rgb[n+at]=Color.green(pixel)/255f; rgb[2*n+at]=Color.blue(pixel)/255f
                    if(x in ox until ox+fitW && y in oy until oy+fitH && selected[(top+sy)*source.width+left+sx].toInt()!=0) inputMask[at]=1f
                }
                currentCoroutineContext().ensureActive()
                val predicted=ModelInference.session(ModelCatalog.file(context,"inpaint","lama_fp32.onnx")).use { session ->
                    ModelInference.floats(rgb,longArrayOf(1,3,512,512)).use { image -> ModelInference.floats(inputMask,longArrayOf(1,1,512,512)).use { erased ->
                        session.run(mapOf("image" to image,"mask" to erased)).use { results ->
                            val tensor=results[0] as OnnxTensor
                            require(tensor.info.shape.contentEquals(longArrayOf(1,3,512,512))) { "修补模型版本不匹配" }
                            ModelInference.copy(tensor)
                        }
                    } }
                }
                currentCoroutineContext().ensureActive()
                val pixels=IntArray(fitW*fitH) { i ->
                    val at=(i/fitW+oy)*512+i%fitW+ox
                    fun channel(offset: Int): Int { val v=predicted[at+offset]; require(v.isFinite()) { "修补输出无效" }; return v.coerceIn(0f,255f).roundToInt() }
                    Color.rgb(channel(0),channel(n),channel(2*n))
                }
                val small=Bitmap.createBitmap(pixels,fitW,fitH,Bitmap.Config.ARGB_8888)
                val full=try { Bitmap.createScaledBitmap(small,w,h,true) } catch(e: Throwable) { small.recycle(); throw e }
                try {
                    val fixed=IntArray(w*h); full.getPixels(fixed,0,w,0,0,w,h)
                    for(y in 0 until h) for(x in 0 until w) if(selected[(top+y)*source.width+left+x].toInt()!=0) {
                        val i=y*w+x; original[i]=(fixed[i] and 0x00ffffff) or (original[i] and 0xff000000.toInt())
                    }
                    requireNotNull(source.copy(Bitmap.Config.ARGB_8888,true)).also { it.setPixels(original,0,w,left,top,w,h) }
                } finally { if(full!==small)full.recycle(); small.recycle() }
            } finally { mask.release() }
        } }
}
