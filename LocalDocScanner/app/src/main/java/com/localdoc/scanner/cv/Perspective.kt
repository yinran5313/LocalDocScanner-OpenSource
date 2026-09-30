package com.localdoc.scanner.cv

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 透视矫正：由四边形求单应矩阵，再做反向映射采样 */

/** 3x3 单应矩阵，行主序 */
typealias Homography = FloatArray

/**
 * 求解把 src 四点映射到 dst 四点的单应矩阵（h[8] 固定为 1）。
 * 使用直接线性变换（DLT）+ 高斯消元。
 */
fun computeHomography(src: List<Point>, dst: List<Point>): Homography? {
    if (src.size != 4 || dst.size != 4) return null

    // A(8x8) * x = b
    val a = Array(8) { FloatArray(8) }
    val b = FloatArray(8)

    for (i in 0 until 4) {
        val x = src[i].x
        val y = src[i].y
        val u = dst[i].x
        val v = dst[i].y

        val rowU = i * 2
        a[rowU][0] = x
        a[rowU][1] = y
        a[rowU][2] = 1f
        a[rowU][3] = 0f
        a[rowU][4] = 0f
        a[rowU][5] = 0f
        a[rowU][6] = -x * u
        a[rowU][7] = -y * u
        b[rowU] = u

        val rowV = i * 2 + 1
        a[rowV][0] = 0f
        a[rowV][1] = 0f
        a[rowV][2] = 0f
        a[rowV][3] = x
        a[rowV][4] = y
        a[rowV][5] = 1f
        a[rowV][6] = -x * v
        a[rowV][7] = -y * v
        b[rowV] = v
    }

    val x = solveLinearSystem(a, b) ?: return null
    return floatArrayOf(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], 1f)
}

/** 高斯消元求解线性方程组，主元选取避免除零 */
private fun solveLinearSystem(a: Array<FloatArray>, b: FloatArray): FloatArray? {
    val n = b.size
    for (col in 0 until n) {
        var pivot = col
        for (row in col + 1 until n) {
            if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
        }
        if (abs(a[pivot][col]) < 1e-9f) return null
        if (pivot != col) {
            val tmpRow = a[col]
            a[col] = a[pivot]
            a[pivot] = tmpRow
            val tmpB = b[col]
            b[col] = b[pivot]
            b[pivot] = tmpB
        }
        val pivotVal = a[col][col]
        for (row in col + 1 until n) {
            val factor = a[row][col] / pivotVal
            if (factor == 0f) continue
            for (k in col until n) {
                a[row][k] -= factor * a[col][k]
            }
            b[row] -= factor * b[col]
        }
    }

    val x = FloatArray(n)
    for (row in n - 1 downTo 0) {
        var sum = b[row]
        for (k in row + 1 until n) {
            sum -= a[row][k] * x[k]
        }
        x[row] = sum / a[row][row]
        if (x[row].isNaN() || x[row].isInfinite()) return null
    }
    return x
}

/** 计算矫正后的输出宽高：取四边形上下边与左右边的平均长度 */
fun outputSizeFor(q: Quad): Pair<Int, Int> {
    fun dist(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
    val width = (dist(q.p0, q.p1) + dist(q.p3, q.p2)) / 2f
    val height = (dist(q.p0, q.p3) + dist(q.p1, q.p2)) / 2f
    return max(64, width.roundToInt()) to max(64, height.roundToInt())
}

/**
 * 把 Bitmap 中 quad 区域矫正为矩形。
 * homography 方向：归一化目标坐标(0..1) → 源图像坐标。
 */
fun warpPerspective(src: Bitmap, quad: Quad, outWidth: Int, outHeight: Int): Bitmap? {
    if (OpenCvDocument.available()) return OpenCvDocument.warp(src, quad, outWidth, outHeight)
    if (outWidth < 2 || outHeight < 2 || outWidth.toLong() * outHeight > 20_000_000L ||
        quad.toList().any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val w = src.width
    val h = src.height

    val srcPts = quad.toList()
    val dstPts = listOf(
        Point(0f, 0f),
        Point(1f, 0f),
        Point(1f, 1f),
        Point(0f, 1f)
    )
    // 目标(归一化) → 源：把 dstPts 当作 src 参数、srcPts 当作 dst 参数
    val homography = computeHomography(dstPts, srcPts) ?: return null

    val srcPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)

    val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    val outPixels = IntArray(outWidth * outHeight)

    for (y in 0 until outHeight) {
        val v = y.toFloat() / (outHeight - 1).coerceAtLeast(1)
        for (x in 0 until outWidth) {
            val u = x.toFloat() / (outWidth - 1).coerceAtLeast(1)

            val denom = homography[6] * u + homography[7] * v + homography[8]
            if (abs(denom) < 1e-6f) continue
            val sx = (homography[0] * u + homography[1] * v + homography[2]) / denom
            val sy = (homography[3] * u + homography[4] * v + homography[5]) / denom

            val px = sx.roundToInt()
            val py = sy.roundToInt()
            if (px < 0 || py < 0 || px >= w || py >= h) continue

            outPixels[y * outWidth + x] = sampleBilinear(srcPixels, w, h, sx, sy)
        }
    }
    out.setPixels(outPixels, 0, outWidth, 0, 0, outWidth, outHeight)
    return out
}

private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
    val x0 = x.toInt().coerceIn(0, w - 1)
    val y0 = y.toInt().coerceIn(0, h - 1)
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = x - x0
    val fy = y - y0

    val p00 = pixels[y0 * w + x0]
    val p10 = pixels[y0 * w + x1]
    val p01 = pixels[y1 * w + x0]
    val p11 = pixels[y1 * w + x1]

    val r = bilinear((p00 shr 16) and 0xFF, (p10 shr 16) and 0xFF, (p01 shr 16) and 0xFF, (p11 shr 16) and 0xFF, fx, fy)
    val g = bilinear((p00 shr 8) and 0xFF, (p10 shr 8) and 0xFF, (p01 shr 8) and 0xFF, (p11 shr 8) and 0xFF, fx, fy)
    val b = bilinear(p00 and 0xFF, p10 and 0xFF, p01 and 0xFF, p11 and 0xFF, fx, fy)

    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun bilinear(v00: Int, v10: Int, v01: Int, v11: Int, fx: Float, fy: Float): Int {
    val top = v00 * (1 - fx) + v10 * fx
    val bottom = v01 * (1 - fx) + v11 * fx
    return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
}
