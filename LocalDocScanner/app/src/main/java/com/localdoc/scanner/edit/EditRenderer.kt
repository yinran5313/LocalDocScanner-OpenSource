package com.localdoc.scanner.edit

import android.graphics.Bitmap
import com.localdoc.scanner.cv.Point
import com.localdoc.scanner.cv.Quad
import com.localdoc.scanner.cv.ScanFilter
import com.localdoc.scanner.cv.outputSizeFor
import com.localdoc.scanner.cv.processScanBitmap
import com.localdoc.scanner.cv.scaleBitmap
import com.localdoc.scanner.cv.warpPerspective
import com.localdoc.scanner.util.ImageIo
import kotlin.math.max

/** 编辑预览、单页保存和批量增强共用同一渲染函数。 */
fun renderProcessed(
    rotated: Bitmap,
    corners: List<NormalizedPoint>,
    filter: ScanFilter,
    brightness: Float,
    contrast: Float,
    maxSide: Int,
    fineRotation: Float = 0f
): Bitmap {
    val safeCorners = corners.takeIf { it.size == 4 && isValidCrop(it) } ?: defaultCropCorners()
    val quad = Quad(
        Point(safeCorners[0].x * rotated.width, safeCorners[0].y * rotated.height),
        Point(safeCorners[1].x * rotated.width, safeCorners[1].y * rotated.height),
        Point(safeCorners[2].x * rotated.width, safeCorners[2].y * rotated.height),
        Point(safeCorners[3].x * rotated.width, safeCorners[3].y * rotated.height)
    )
    val (rawWidth, rawHeight) = outputSizeFor(quad)
    val cap = (maxSide.toFloat() / max(rawWidth, rawHeight)).coerceAtMost(1f)
    val width = max(64, (rawWidth * cap).toInt())
    val height = max(64, (rawHeight * cap).toInt())
    val warped = warpPerspective(rotated, quad, width, height) ?: scaleBitmap(rotated, maxSide)
    val straightened = if (fineRotation == 0f) warped else ImageIo.rotateCropped(warped, fineRotation)
    val processed = processScanBitmap(straightened, filter, brightness, contrast)
    if (straightened !== warped && straightened !== processed) straightened.recycle()
    if (warped !== rotated && warped !== straightened && warped !== processed) warped.recycle()
    return processed
}
