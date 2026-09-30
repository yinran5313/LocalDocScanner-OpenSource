package com.localdoc.scanner.cv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 针对纸张、票据的局部自适应二值化。
 *
 * 每个像素使用邻域均值与方差计算 Sauvola 阈值，避免一处阴影或折痕改变整页阈值。
 * 返回值 0 表示黑，255 表示白。该函数不依赖 Android，便于用合成阴影样本做回归测试。
 */
fun adaptiveDocumentThreshold(
    gray: IntArray,
    width: Int,
    height: Int,
    radius: Int = defaultAdaptiveRadius(width, height),
    sensitivity: Double = 0.22
): IntArray {
    require(width > 0 && height > 0) { "width and height must be positive" }
    require(gray.size == width * height) { "gray size does not match image size" }

    val safeRadius = radius.coerceIn(2, max(2, min(width, height) / 2))
    val safeSensitivity = sensitivity.coerceIn(0.05, 0.5)
    val stride = width + 1
    val integral = LongArray((width + 1) * (height + 1))
    val squaredIntegral = LongArray((width + 1) * (height + 1))

    for (y in 0 until height) {
        var rowSum = 0L
        var rowSquaredSum = 0L
        for (x in 0 until width) {
            val value = gray[y * width + x].coerceIn(0, 255).toLong()
            rowSum += value
            rowSquaredSum += value * value
            val target = (y + 1) * stride + x + 1
            integral[target] = integral[y * stride + x + 1] + rowSum
            squaredIntegral[target] = squaredIntegral[y * stride + x + 1] + rowSquaredSum
        }
    }

    fun regionSum(table: LongArray, left: Int, top: Int, right: Int, bottom: Int): Long {
        val x0 = left
        val y0 = top
        val x1 = right + 1
        val y1 = bottom + 1
        return table[y1 * stride + x1] - table[y0 * stride + x1] -
            table[y1 * stride + x0] + table[y0 * stride + x0]
    }

    val out = IntArray(gray.size)
    for (y in 0 until height) {
        val top = max(0, y - safeRadius)
        val bottom = min(height - 1, y + safeRadius)
        for (x in 0 until width) {
            val left = max(0, x - safeRadius)
            val right = min(width - 1, x + safeRadius)
            val count = (right - left + 1) * (bottom - top + 1)
            val sum = regionSum(integral, left, top, right, bottom).toDouble()
            val squared = regionSum(squaredIntegral, left, top, right, bottom).toDouble()
            val mean = sum / count
            val variance = max(0.0, squared / count - mean * mean)
            val deviation = sqrt(variance)
            val threshold = mean * (1.0 + safeSensitivity * (deviation / 128.0 - 1.0))
            out[y * width + x] = if (gray[y * width + x] <= threshold) 0 else 255
        }
    }
    return out
}

fun defaultAdaptiveRadius(width: Int, height: Int): Int {
    val shortSide = min(width, height).coerceAtLeast(1)
    return (shortSide / 28).coerceIn(12, 64)
}

/** 把局部背景亮度拉到相近水平，保留文字与印章的灰阶，不做二值化。 */
fun normalizeDocumentIllumination(
    gray: IntArray,
    width: Int,
    height: Int,
    radius: Int = (defaultAdaptiveRadius(width, height) * 2).coerceAtMost(96),
    targetBackground: Int = 224
): IntArray {
    require(width > 0 && height > 0 && gray.size == width * height)
    val safeRadius = radius.coerceIn(2, max(2, min(width, height) / 2))
    val stride = width + 1
    val integral = LongArray((width + 1) * (height + 1))
    for (y in 0 until height) {
        var row = 0L
        for (x in 0 until width) {
            row += gray[y * width + x].coerceIn(0, 255)
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
        }
    }
    fun sum(left: Int, top: Int, right: Int, bottom: Int): Long =
        integral[(bottom + 1) * stride + right + 1] - integral[top * stride + right + 1] -
            integral[(bottom + 1) * stride + left] + integral[top * stride + left]

    return IntArray(gray.size) { index ->
        val x = index % width
        val y = index / width
        val left = max(0, x - safeRadius)
        val right = min(width - 1, x + safeRadius)
        val top = max(0, y - safeRadius)
        val bottom = min(height - 1, y + safeRadius)
        val count = (right - left + 1) * (bottom - top + 1)
        val localMean = sum(left, top, right, bottom).toDouble() / count
        (gray[index] + targetBackground - localMean).toInt().coerceIn(0, 255)
    }
}

/**
 * 清除与页面边缘相连的大块黑色背景/阴影。只处理超过阈值的大连通块，细小的边缘文字仍保留。
 */
fun clearLargeBorderShadows(
    binary: IntArray,
    width: Int,
    height: Int,
    minimumFraction: Float = 0.012f
): IntArray {
    require(width > 0 && height > 0 && binary.size == width * height)
    val result = binary.copyOf()
    val visited = BooleanArray(binary.size)
    val queue = IntArray(binary.size)
    val minimumSize = (binary.size * minimumFraction.coerceIn(0.001f, 0.2f)).toInt().coerceAtLeast(8)

    fun inspect(start: Int) {
        if (visited[start] || binary[start] > 127) return
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            fun add(next: Int) {
                if (!visited[next] && binary[next] <= 127) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            if (x > 0) add(index - 1)
            if (x + 1 < width) add(index + 1)
            if (y > 0) add(index - width)
            if (y + 1 < height) add(index + width)
        }
        if (tail >= minimumSize) for (i in 0 until tail) result[queue[i]] = 255
    }

    for (x in 0 until width) {
        inspect(x)
        inspect((height - 1) * width + x)
    }
    for (y in 0 until height) {
        inspect(y * width)
        inspect(y * width + width - 1)
    }
    return result
}
