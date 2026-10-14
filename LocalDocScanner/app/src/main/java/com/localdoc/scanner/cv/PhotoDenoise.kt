package com.localdoc.scanner.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** OpenCV Photo.fastNlMeansDenoisingColored (Apache-2.0). No extra model needed. */
object PhotoDenoise {
    fun run(source: Bitmap, strength: Float): Bitmap {
        require(OpenCvDocument.available()) { "图像处理组件未加载" }
        require(strength.isFinite() && strength in 0f..1f)
        val rgba=Mat(); val bgr=Mat(); val result=Mat(); val fixed=Mat()
        try {
            Utils.bitmapToMat(source,rgba,true); Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR)
            Photo.fastNlMeansDenoisingColored(bgr,result,3+strength*7,3+strength*7,7,21)
            Imgproc.cvtColor(result,fixed,Imgproc.COLOR_BGR2RGBA)
            val bitmap=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(fixed,bitmap)
            if(source.hasAlpha()) {
                val original=IntArray(source.width); val row=IntArray(source.width)
                for(y in 0 until source.height) { source.getPixels(original,0,source.width,0,y,source.width,1); bitmap.getPixels(row,0,source.width,0,y,source.width,1)
                    for(x in row.indices) row[x]=(row[x] and 0xffffff) or (original[x] and 0xff000000.toInt())
                    bitmap.setPixels(row,0,source.width,0,y,source.width,1)
                }
            }
            return bitmap
        } finally { rgba.release(); bgr.release(); result.release(); fixed.release() }
    }
}
