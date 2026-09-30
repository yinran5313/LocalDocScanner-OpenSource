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
