package com.localdoc.scanner.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Reuses OpenCV's LAB channel filtering (Apache-2.0). Keep luminance/text edges intact.
 *  Mild chroma smoothing reduces coloured interference; monochrome moire and clipped glare need a new shot. */
object ProjectionEnhancement {
    fun apply(source: Bitmap): Bitmap? {
        if (!OpenCvDocument.available()) return null
        val rgba=Mat(); val rgb=Mat(); val lab=Mat(); val channels=mutableListOf<Mat>()
        try {
            Utils.bitmapToMat(source,rgba); Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(rgb,lab,Imgproc.COLOR_RGB2Lab)
            Core.split(lab,channels)
            for(i in 1..2) Imgproc.GaussianBlur(channels[i],channels[i],Size(3.0,3.0),.7)
            Core.merge(channels,lab); Imgproc.cvtColor(lab,rgb,Imgproc.COLOR_Lab2RGB); Imgproc.cvtColor(rgb,rgba,Imgproc.COLOR_RGB2RGBA)
            return Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba,it) }
        } finally { channels.forEach { it.release() }; rgba.release(); rgb.release(); lab.release() }
    }
}
