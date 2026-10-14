package com.localdoc.scanner.cv

import android.graphics.Bitmap
import com.localdoc.scanner.pdf.PdfInkPoint
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** OpenCV Photo.inpaint (Apache-2.0). Only selected pixels change; no invented OCR text. */
object RegionRepair {
    internal fun selectionMask(width: Int, height: Int, strokes: List<List<PdfInkPoint>>, brush: Float): Mat {
        require(OpenCvDocument.available()) { "图像处理组件未加载，请重试" }
        require(width>0 && height>0)
        require(strokes.isNotEmpty() && strokes.size <= 1000 && strokes.sumOf { it.size } <= 50_000) { "请先涂抹需要擦除的区域" }
        require(brush.isFinite() && brush in 0.005f..0.15f)
        require(strokes.flatten().all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "选区坐标无效" }
        val mask=Mat.zeros(height,width,CvType.CV_8UC1)
        try {
            val thickness = (brush * minOf(width,height)).toInt().coerceAtLeast(1)
            fun point(p: PdfInkPoint) = Point(p.x*(width-1).toDouble(),p.y*(height-1).toDouble())
            strokes.forEach { stroke ->
                stroke.forEach { Imgproc.circle(mask,point(it),maxOf(1,thickness/2),Scalar(255.0),-1) }
                stroke.zipWithNext().forEach { (a,b) -> Imgproc.line(mask,point(a),point(b),Scalar(255.0),thickness) }
            }
            val selected = Core.countNonZero(mask)
            require(selected > 0 && selected.toDouble()/(width.toLong()*height) < 0.35) { "选区超过图像35%，请分次修补" }
            return mask
        } catch(e: Throwable) { mask.release(); throw e }
    }

    fun repair(source: Bitmap, strokes: List<List<PdfInkPoint>>, brush: Float): Bitmap {
        val mask=selectionMask(source.width,source.height,strokes,brush)
        val rgba=Mat(); val rgb=Mat(); val output=Mat(); val fixed=Mat()
        try {
            Utils.bitmapToMat(source,rgba,true); Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
            Photo.inpaint(rgb,mask,output,3.0,Photo.INPAINT_TELEA)
            Imgproc.cvtColor(output,fixed,Imgproc.COLOR_RGB2RGBA)
            val candidate=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(fixed,candidate)
                val selected=ByteArray(source.width*source.height);mask.get(0,0,selected)
                val original=IntArray(selected.size);val pixels=IntArray(selected.size)
                source.getPixels(original,0,source.width,0,0,source.width,source.height)
                candidate.getPixels(pixels,0,source.width,0,0,source.width,source.height)
                val result=requireNotNull(source.copy(Bitmap.Config.ARGB_8888,true))
                for(y in 0 until source.height) {
                    var x=0
                    while(x<source.width) {
                        if(selected[y*source.width+x].toInt()==0) { x++;continue }
                        val start=x
                        while(x<source.width && selected[y*source.width+x].toInt()!=0) { val i=y*source.width+x;pixels[i]=(pixels[i] and 0xffffff) or (original[i] and 0xff000000.toInt());x++ }
                        result.setPixels(pixels,y*source.width+start,source.width,start,y,x-start,1)
                    }
                }
                return result
            } finally { candidate.recycle() }
        } finally { rgba.release(); rgb.release(); mask.release(); output.release(); fixed.release() }
    }
}
