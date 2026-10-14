package com.localdoc.scanner.cv

import android.graphics.Bitmap
import com.localdoc.scanner.pdf.PdfInkPoint
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class RepairCandidate(val kind: String, val left: Float, val top: Float, val right: Float, val bottom: Float,
    val strokes: List<List<PdfInkPoint>>, val brush: Float)

/** Conservative colour/connected-region proposals, not a semantic hand classifier.
 * Border-connected YCrCb skin candidates and bright islands with a darker surround.
 * Never apply without selection review; white paper and coloured printing may be ambiguous.
 */
object ObstructionDetector {
    fun detect(source: Bitmap): List<RepairCandidate> {
        require(OpenCvDocument.available()) { "图像处理组件未加载" }
        val ratio=minOf(1f,768f/maxOf(source.width,source.height))
        val image=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*ratio).toInt()),maxOf(1,(source.height*ratio).toInt()),true)
        val rgba=Mat(); val rgb=Mat(); val ycc=Mat(); val gray=Mat(); val skin=Mat(); val bright=Mat()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(5.0,5.0))
        try {
            Utils.bitmapToMat(image,rgba,true); Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb,ycc,Imgproc.COLOR_RGB2YCrCb); Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY)
            Core.inRange(ycc,Scalar(30.0,135.0,80.0),Scalar(245.0,180.0,135.0),skin)
            Core.inRange(rgb,Scalar(246.0,246.0,246.0),Scalar(255.0,255.0,255.0),bright)
            Imgproc.morphologyEx(skin,skin,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.morphologyEx(bright,bright,Imgproc.MORPH_OPEN,kernel)
            return components(skin,gray,"手指候选")+components(bright,gray,"反光候选")
        } finally { rgba.release(); rgb.release(); ycc.release(); gray.release(); skin.release(); bright.release(); kernel.release(); if(image!==source) image.recycle() }
    }
    private fun components(mask: Mat, gray: Mat, kind: String): List<RepairCandidate> {
        val labels=Mat(); val stats=Mat(); val centres=Mat()
        try {
            val count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centres)
            val w=mask.cols(); val h=mask.rows(); val total=w*h; val found=mutableListOf<RepairCandidate>()
            val ids=IntArray(total); labels.get(0,0,ids)
            val luminance=ByteArray(total); gray.get(0,0,luminance)
            for(id in 1 until count) {
                val x=stats.get(id,Imgproc.CC_STAT_LEFT)[0].toInt(); val y=stats.get(id,Imgproc.CC_STAT_TOP)[0].toInt()
                val rw=stats.get(id,Imgproc.CC_STAT_WIDTH)[0].toInt(); val rh=stats.get(id,Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area=stats.get(id,Imgproc.CC_STAT_AREA)[0].toInt()
                if(area < maxOf(24,total/1500) || area>total*.16 || rw<4 || rh<4) continue
                if(kind=="手指候选") {
                    if(!(x< w*.035 || y<h*.035 || x+rw>w*.965 || y+rh>h*.965)) continue
                    if(area.toFloat()/(rw*rh)<.25f) continue
                } else {
                    // A full white sheet is not glare. Require a measurable dark surrounding ring.
                    val margin=maxOf(8,minOf(rw,rh)/5); var sum=0L; var n=0
                    for(yy in maxOf(0,y-margin) until minOf(h,y+rh+margin)) for(xx in maxOf(0,x-margin) until minOf(w,x+rw+margin)) {
                        if(xx !in x until x+rw || yy !in y until y+rh) { sum+=luminance[yy*w+xx].toInt() and 255; n++ }
                    }
                    if(n==0 || sum.toFloat()/n>225f || area.toFloat()/(rw*rh)<.35f) continue
                }
                val stride=maxOf(2,(minOf(w,h)*.008f).toInt())
                val strokes=mutableListOf<List<PdfInkPoint>>()
                for(yy in y until y+rh step stride) {
                    var xx=x
                    while(xx<x+rw) { if(ids[yy*w+xx]!=id) { xx++; continue }
                        val start=xx; while(xx<x+rw && ids[yy*w+xx]==id) xx++
                        strokes+=listOf(PdfInkPoint(start.toFloat()/(w-1).coerceAtLeast(1),yy.toFloat()/(h-1).coerceAtLeast(1)),PdfInkPoint((xx-1).toFloat()/(w-1).coerceAtLeast(1),yy.toFloat()/(h-1).coerceAtLeast(1)))
                    }
                }
                if(strokes.size in 1..700) found+=RepairCandidate(kind,x.toFloat()/w,y.toFloat()/h,(x+rw).toFloat()/w,(y+rh).toFloat()/h,strokes,(stride*1.2f/minOf(w,h)).coerceIn(.005f,.15f))
                if(found.size>=6) break
            }
            return found
        } finally { labels.release(); stats.release(); centres.release() }
    }
}
