package com.localdoc.scanner.cv

import android.graphics.Bitmap
import kotlin.math.max

data class DocumentQualityResult(
    val meanLuma: Float,
    val darkRatio: Float,
    val highlightRatio: Float,
    val sharpness: Float,
    val warnings: List<String>
)

/** 只给拍摄提示，不修改或删除原图。 */
object DocumentQuality {
    fun analyze(bitmap: Bitmap, maxSamples: Int = 180_000): DocumentQualityResult {
        val pixelCount = bitmap.width * bitmap.height
        val stride = max(1, kotlin.math.sqrt(pixelCount.toDouble() / maxSamples).toInt())
        val sampledWidth = (bitmap.width + stride - 1) / stride
        val sampledHeight = (bitmap.height + stride - 1) / stride
        val luma = IntArray(sampledWidth * sampledHeight)
        var target = 0
        val row = IntArray(bitmap.width)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val color = row[x]
                luma[target++] = (((color ushr 16) and 0xFF) * 299 +
                    ((color ushr 8) and 0xFF) * 587 + (color and 0xFF) * 114) / 1000
                x += stride
            }
            y += stride
        }
        return analyzeLuma(luma, sampledWidth, sampledHeight)
    }

    fun analyzeLuma(luma: IntArray, width: Int, height: Int): DocumentQualityResult {
        require(width > 0 && height > 0 && luma.size >= width * height)
        val count = width * height
        var sum = 0L
        var dark = 0
        var highlight = 0
        for (i in 0 until count) {
            val value = luma[i].coerceIn(0, 255)
            sum += value
            if (value < 45) dark++
            if (value > 248) highlight++
        }
        val mean = sum.toFloat() / count
        val darkRatio = dark.toFloat() / count
        val highlightRatio = highlight.toFloat() / count

        var lapSum = 0.0
        var lapSquared = 0.0
        var lapCount = 0
        if (width >= 3 && height >= 3) {
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val center = luma[y * width + x]
                    val lap = luma[(y - 1) * width + x] + luma[(y + 1) * width + x] +
                        luma[y * width + x - 1] + luma[y * width + x + 1] - 4 * center
                    lapSum += lap
                    lapSquared += lap.toDouble() * lap
                    lapCount++
                }
            }
        }
        val sharpness = if (lapCount == 0) 0f else {
            val average = lapSum / lapCount
            (lapSquared / lapCount - average * average).coerceAtLeast(0.0).toFloat()
        }

        val warnings = buildList {
            if (sharpness < 55f) add("画面可能模糊，请稳住手机或重新对焦")
            if (mean < 72f || darkRatio > 0.50f) add("画面偏暗，请增加光线")
            // White paper is expected. Global bright-pixel count cannot distinguish paper from glare.
            if (mean > 248f && highlightRatio > 0.98f && sharpness < 55f) add("画面过亮，请避开强光")
            if (localizedGlare(luma, width, height)) add("可能有局部反光，请稍微改变拍摄角度")
        }
        return DocumentQualityResult(mean, darkRatio, highlightRatio, sharpness, warnings)
    }

    private fun localizedGlare(luma: IntArray, width: Int, height: Int): Boolean {
        val count = width * height
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        for (start in 0 until count) {
            if (visited[start] || luma[start] < 254) continue
            var head = 0; var tail = 1; var boundary = 0; var darkBoundary = 0
            queue[0] = start; visited[start] = true
            while (head < tail) {
                val at = queue[head++]; val x = at % width; val y = at / width
                for (neighbor in intArrayOf(if (x > 0) at - 1 else -1, if (x < width - 1) at + 1 else -1,
                    if (y > 0) at - width else -1, if (y < height - 1) at + width else -1)) {
                    if (neighbor < 0) continue
                    if (luma[neighbor] < 254) { boundary++; if (luma[neighbor] < 190) darkBoundary++ }
                    else if (!visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor }
                }
            }
            // Saturated compact region against a darker surface; a full bright page is excluded.
            val fraction = tail.toFloat() / count
            if (fraction in 0.008f..0.30f && boundary > 0 && darkBoundary.toFloat() / boundary > 0.45f) return true
        }
        return false
    }
}
