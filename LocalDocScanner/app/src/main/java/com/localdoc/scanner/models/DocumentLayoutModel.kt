package com.localdoc.scanner.models

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import com.localdoc.scanner.structure.*

/** Apache-2.0 RapidLayout PPPreProcess/PPPostProcess, fixed CDLA PicoDet ONNX. */
object DocumentLayoutModel {
    suspend fun detect(context: Context, image: Bitmap): List<LayoutRegion> = withContext(Dispatchers.Default) { ModelInference.gate.withLock {
        val data=ModelInference.pixels(image,608,800,bgr=true)
        val n=608*800; val mean=floatArrayOf(.485f,.456f,.406f); val std=floatArrayOf(.229f,.224f,.225f)
        for(c in 0..2) for(i in 0 until n) data[c*n+i]=(data[c*n+i]-mean[c])/std[c]
        currentCoroutineContext().ensureActive()
        ModelInference.session(ModelCatalog.file(context,"layout","layout_cdla.onnx")).use { session ->
            ModelInference.floats(data,longArrayOf(1,3,800,608)).use { input -> session.run(mapOf("image" to input)).use { output ->
                require(output.size()==8) { "版面模型版本不匹配" }
                PicoLayoutDecode.decode((0..7).map { ModelInference.copy(output[it] as OnnxTensor) })
            } }
        }
    } }
}
