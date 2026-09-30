package com.localdoc.scanner.cv

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 图像算法：不引入 OpenCV so，保证 APK 体积与构建可靠性。
 * 所有算法针对降采样后的预览图运行（通常 400~800 px 宽），单帧耗时控制在几十毫秒。
 */

data class Point(val x: Float, val y: Float)

/** 四边形，顺序为 左上 → 右上 → 右下 → 左下 */
data class Quad(val p0: Point, val p1: Point, val p2: Point, val p3: Point) {
    fun toList(): List<Point> = listOf(p0, p1, p2, p3)
}

/** 缩放 Bitmap，避免在大图上直接运算 */
fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val scale = maxSide.toFloat() / maxOf(w, h).toFloat()
    if (scale >= 1f) return src
    return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
}

/** 转灰度，返回 IntArray（0..255） */
fun toGray(src: Bitmap): Pair<IntArray, Int> {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val gray = IntArray(w * h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }
    return gray to w
}

/** 3x3 高斯模糊 */
fun gaussianBlur(gray: IntArray, w: Int, h: Int): IntArray {
    val kernel = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
    val out = IntArray(gray.size)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            var sum = 0
            for (ky in -1..1) {
                for (kx in -1..1) {
                    val idx = (y + ky) * w + (x + kx)
                    sum += gray[idx] * kernel[(ky + 1) * 3 + (kx + 1)]
                }
            }
            out[y * w + x] = sum / 16
        }
    }
    return out
}

/** Sobel 梯度，返回梯度幅值与方向 */
fun sobel(gray: IntArray, w: Int, h: Int): Pair<IntArray, FloatArray> {
    val mag = IntArray(gray.size)
    val dir = FloatArray(gray.size)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val i00 = gray[(y - 1) * w + (x - 1)]
            val i01 = gray[(y - 1) * w + x]
            val i02 = gray[(y - 1) * w + (x + 1)]
            val i10 = gray[y * w + (x - 1)]
            val i12 = gray[y * w + (x + 1)]
            val i20 = gray[(y + 1) * w + (x - 1)]
            val i21 = gray[(y + 1) * w + x]
            val i22 = gray[(y + 1) * w + (x + 1)]
            val gx = -i00 + i02 - 2 * i10 + 2 * i12 - i20 + i22
            val gy = i00 + 2 * i01 + i02 - i20 - 2 * i21 - i22
            val idx = y * w + x
            mag[idx] = min(255, sqrt((gx * gx + gy * gy).toDouble()).toInt())
            dir[idx] = kotlin.math.atan2(gy.toDouble(), gx.toDouble()).toFloat()
        }
    }
    return mag to dir
}

/** 非极大值抑制 + 双阈值（简化 Canny） */
fun canny(gray: IntArray, w: Int, h: Int, lowRatio: Float = 0.1f, highRatio: Float = 0.25f): IntArray {
    val blurred = gaussianBlur(gray, w, h)
    val (mag, dir) = sobel(blurred, w, h)
    var maxMag = 1
    for (v in mag) if (v > maxMag) maxMag = v
    val high = (maxMag * highRatio).toInt().coerceAtLeast(1)
    val low = (maxMag * lowRatio).toInt().coerceAtLeast(1)

    val out = IntArray(mag.size)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            val m = mag[idx]
            if (m < low) continue
            val angle = dir[idx]
            val quantized = ((angle + Math.PI) * 4 / Math.PI).toInt() % 4
            val neighbor1: Int
            val neighbor2: Int
            when (quantized) {
                0 -> { neighbor1 = mag[idx - 1]; neighbor2 = mag[idx + 1] }
                1 -> { neighbor1 = mag[(y - 1) * w + x + 1]; neighbor2 = mag[(y + 1) * w + x - 1] }
                2 -> { neighbor1 = mag[(y - 1) * w + x]; neighbor2 = mag[(y + 1) * w + x] }
                else -> { neighbor1 = mag[(y - 1) * w + x - 1]; neighbor2 = mag[(y + 1) * w + x + 1] }
            }
            if (m >= neighbor1 && m >= neighbor2 && m >= high) {
                out[idx] = 255
            }
        }
    }
    return out
}

/**
 * 从边缘图中找出最大的近似四边形。
 * 先膨胀连接断裂边缘，再按连通区域分别求四角，避免把桌面纹理和纸张边缘混成一个四边形。
 */
fun detectDocumentQuad(edges: IntArray, w: Int, h: Int): Quad? {
    if (w < 8 || h < 8 || edges.size < w * h) return null
    val margin = (min(w, h) * 0.02f).toInt()
    val connected = dilateEdges(edges, w, h, radius = 2)
    val visited = BooleanArray(w * h)
    val queue = IntArray(w * h)
    var best: Quad? = null
    var bestArea = 0f
    val minimumComponent = max(30, min(w, h) / 3)

    for (startY in margin until h - margin) {
        for (startX in margin until w - margin) {
            val start = startY * w + startX
            if (visited[start] || connected[start] == 0) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minSum = Float.POSITIVE_INFINITY
            var maxSum = Float.NEGATIVE_INFINITY
            var minDiff = Float.POSITIVE_INFINITY
            var maxDiff = Float.NEGATIVE_INFINITY
            var lt = Point(startX.toFloat(), startY.toFloat())
            var rb = lt
            var rt = lt
            var lb = lt

            while (head < tail) {
                val index = queue[head++]
                val x = index % w
                val y = index / w
                count++
                val sum = x + y.toFloat()
                val diff = x - y.toFloat()
                if (sum < minSum) { minSum = sum; lt = Point(x.toFloat(), y.toFloat()) }
                if (sum > maxSum) { maxSum = sum; rb = Point(x.toFloat(), y.toFloat()) }
                if (diff > maxDiff) { maxDiff = diff; rt = Point(x.toFloat(), y.toFloat()) }
                if (diff < minDiff) { minDiff = diff; lb = Point(x.toFloat(), y.toFloat()) }

                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in margin until w - margin || ny !in margin until h - margin) continue
                    val next = ny * w + nx
                    if (!visited[next] && connected[next] != 0) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }

            if (count < minimumComponent) continue
            val candidate = Quad(lt, rt, rb, lb)
            val area = polygonArea(candidate.toList())
            if (area > bestArea && isSaneQuad(candidate, w, h)) {
                best = candidate
                bestArea = area
            }
        }
    }
    return best
}

private fun dilateEdges(edges: IntArray, w: Int, h: Int, radius: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (edges[y * w + x] == 0) continue
            for (dy in -radius..radius) {
                val ny = y + dy
                if (ny !in 0 until h) continue
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) out[ny * w + nx] = 255
                }
            }
        }
    }
    return out
}

/** 过滤明显不合理的四边形（太小、自交、凹形） */
fun isSaneQuad(q: Quad, w: Int, h: Int): Boolean {
    val pts = q.toList()
    val area = polygonArea(pts)
    val imageArea = (w * h).toFloat()
    if (area < imageArea * 0.08f) return false
    if (area > imageArea * 0.98f) return false
    return isConvex(pts)
}

fun polygonArea(pts: List<Point>): Float {
    var sum = 0f
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        sum += a.x * b.y - b.x * a.y
    }
    return abs(sum) / 2f
}

fun isConvex(pts: List<Point>): Boolean {
    var sign = 0
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        val c = pts[(i + 2) % pts.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (abs(cross) < 1e-3f) continue
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}
