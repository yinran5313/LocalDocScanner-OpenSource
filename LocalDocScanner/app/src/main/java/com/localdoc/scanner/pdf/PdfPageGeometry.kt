package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix

/** Inverts PDFBox's page display transform: CropBox offsets and clockwise /Rotate.
 * See PDFRenderer.transform in PdfBox-Android (Apache-2.0).
 */
internal class PdfPageGeometry(page: PDPage) {
    private val crop = page.cropBox
    private val rotation = ((page.rotation % 360) + 360) % 360
    val width = if (rotation == 90 || rotation == 270) crop.height else crop.width
    val height = if (rotation == 90 || rotation == 270) crop.width else crop.height
    val displayBox get() = PDRectangle(width, height)
    val displayToPdf: Matrix get() = when (rotation) {
        90 -> Matrix(0f, 1f, -1f, 0f, crop.upperRightX, crop.lowerLeftY)
        180 -> Matrix(-1f, 0f, 0f, -1f, crop.upperRightX, crop.upperRightY)
        270 -> Matrix(0f, -1f, 1f, 0f, crop.lowerLeftX, crop.upperRightY)
        else -> Matrix(1f, 0f, 0f, 1f, crop.lowerLeftX, crop.lowerLeftY)
    }
    fun normalizedRectangle(rect:PDRectangle):NormalizedRect {
        fun point(x:Float,y:Float):Pair<Float,Float> = when(rotation) {
            90 -> (y-crop.lowerLeftY)/crop.height to (x-crop.lowerLeftX)/crop.width
            180 -> (crop.upperRightX-x)/crop.width to (y-crop.lowerLeftY)/crop.height
            270 -> (crop.upperRightY-y)/crop.height to (crop.upperRightX-x)/crop.width
            else -> (x-crop.lowerLeftX)/crop.width to (crop.upperRightY-y)/crop.height
        }
        val corners=listOf(point(rect.lowerLeftX,rect.lowerLeftY),point(rect.upperRightX,rect.lowerLeftY),point(rect.lowerLeftX,rect.upperRightY),point(rect.upperRightX,rect.upperRightY))
        val x=corners.minOf {it.first};val y=corners.minOf {it.second};val w=corners.maxOf {it.first}-x;val h=corners.maxOf {it.second}-y
        require(listOf(x,y,w,h).all {it.isFinite()} && w>0 && h>0 && x>=0 && y>=0 && x+w<=1.0001f && y+h<=1.0001f)
        return NormalizedRect(x,y,w.coerceAtMost(1f-x),h.coerceAtMost(1f-y))
    }
    fun point(u: Float, v: Float): Pair<Float, Float> {
        require(u.isFinite() && v.isFinite())
        val x = u.coerceIn(0f, 1f); val y = v.coerceIn(0f, 1f)
        return when (rotation) {
            90 -> crop.lowerLeftX + y * crop.width to crop.lowerLeftY + x * crop.height
            180 -> crop.upperRightX - x * crop.width to crop.lowerLeftY + y * crop.height
            270 -> crop.upperRightX - y * crop.width to crop.upperRightY - x * crop.height
            else -> crop.lowerLeftX + x * crop.width to crop.upperRightY - y * crop.height
        }
    }
    fun quad(x: Float, y: Float, w: Float, h: Float): FloatArray {
        require(listOf(x,y,w,h).all { it.isFinite() })
        val left = x.coerceIn(0f, .99f); val top = y.coerceIn(0f, .99f)
        val right = (left + w.coerceAtLeast(.005f)).coerceAtMost(1f)
        val bottom = (top + h.coerceAtLeast(.005f)).coerceAtMost(1f)
        return listOf(point(left,top),point(right,top),point(left,bottom),point(right,bottom))
            .flatMap { listOf(it.first,it.second) }.toFloatArray()
    }
    fun rectangle(quad: FloatArray): PDRectangle {
        val xs = quad.filterIndexed { i,_ -> i % 2 == 0 }
        val ys = quad.filterIndexed { i,_ -> i % 2 == 1 }
        return PDRectangle(xs.min(),ys.min(),xs.max()-xs.min(),ys.max()-ys.min())
    }
}
