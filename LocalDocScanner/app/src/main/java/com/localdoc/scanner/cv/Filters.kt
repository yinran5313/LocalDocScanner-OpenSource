package com.localdoc.scanner.cv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 扫描件滤镜：全部为纯 Kotlin 实现 */

enum class ScanFilter(val label: String) {
    ORIGINAL("原图"),
    AUTO("自动增强"),
    GRAY("灰度"),
    BLACK_WHITE("黑白"),
    COLOR_BOOST("彩色增强")
}

fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
    if (filter == ScanFilter.ORIGINAL) return src
    if (filter == ScanFilter.AUTO) OpenCvDocument.flattenIllumination(src)?.let { return it }
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    when (filter) {
        ScanFilter.ORIGINAL -> Unit
        ScanFilter.GRAY -> toGrayInPlace(pixels)
        ScanFilter.BLACK_WHITE -> {
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = (((p shr 16) and 0xFF) * 0.299f +
                    ((p shr 8) and 0xFF) * 0.587f +
                    (p and 0xFF) * 0.114f).roundToInt().coerceIn(0, 255)
            }
            val normalized = normalizeDocumentIllumination(gray, w, h)
            val binary = clearLargeBorderShadows(
                adaptiveDocumentThreshold(normalized, w, h, sensitivity = 0.26),
                w,
                h
            )
            for (i in pixels.indices) {
                val v = binary[i]
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        ScanFilter.AUTO -> autoEnhance(pixels, w, h)
        ScanFilter.COLOR_BOOST -> colorBoost(pixels)
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/**
 * 编辑预览和最终导出共用的完整处理顺序。
 * 黑白模式先调整亮度/对比度，再计算局部阈值，避免二值化后再调节造成断字和死黑。
 */
fun processScanBitmap(
    src: Bitmap,
    filter: ScanFilter,
    brightness: Float,
    contrast: Float
): Bitmap {
    if (filter == ScanFilter.BLACK_WHITE) {
        val adjusted = adjustBrightnessContrast(src, brightness, contrast)
        val result = applyFilter(adjusted, filter)
        if (adjusted !== src && adjusted !== result) adjusted.recycle()
        return result
    }

    val filtered = applyFilter(src, filter)
    val result = adjustBrightnessContrast(filtered, brightness, contrast)
    if (filtered !== src && filtered !== result) filtered.recycle()
    return result
}

/** 在滤镜之后统一应用亮度和对比度，预览与最终导出共用。 */
fun adjustBrightnessContrast(src: Bitmap, brightness: Float, contrast: Float): Bitmap {
    val safeBrightness = brightness.coerceIn(-1f, 1f)
    val safeContrast = contrast.coerceIn(0.5f, 1.8f)
    if (safeBrightness == 0f && safeContrast == 1f) return src

    val pixels = IntArray(src.width * src.height)
    src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    val add = safeBrightness * 255f
    fun channel(value: Int): Int = (((value - 128f) * safeContrast) + 128f + add)
        .roundToInt()
        .coerceIn(0, 255)

    for (i in pixels.indices) {
        val p = pixels[i]
        pixels[i] = Color.argb(
            (p ushr 24) and 0xFF,
            channel((p ushr 16) and 0xFF),
            channel((p ushr 8) and 0xFF),
            channel(p and 0xFF)
        )
    }
    return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
    }
}

private fun toGrayInPlace(pixels: IntArray) {
    for (i in pixels.indices) {
        val p = pixels[i]
        val g = ((p shr 16) and 0xFF) * 0.299f +
            ((p shr 8) and 0xFF) * 0.587f +
            (p and 0xFF) * 0.114f
        val v = g.toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}

/** 自动对比度拉伸：按 1%~99% 分位点拉伸 */
private fun autoEnhance(pixels: IntArray, width: Int, height: Int) {
    val histogram = IntArray(256)
    val luma = IntArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        val g = (((p shr 16) and 0xFF) * 0.299f +
            ((p shr 8) and 0xFF) * 0.587f +
            (p and 0xFF) * 0.114f).toInt().coerceIn(0, 255)
        luma[i] = g
    }
    val normalized = normalizeDocumentIllumination(luma, width, height)
    normalized.forEach { histogram[it]++ }
    val total = pixels.size
    var low = 0
    var high = 255
    var acc = 0
    val lowLimit = (total * 0.01f).toInt()
    val highLimit = (total * 0.99f).toInt()
    for (v in 0..255) {
        acc += histogram[v]
        if (acc >= lowLimit) { low = v; break }
    }
    acc = 0
    for (v in 255 downTo 0) {
        acc += histogram[v]
        if (acc >= total - highLimit) { high = v; break }
    }
    val range = max(1, high - low)
    for (i in pixels.indices) {
        val stretched = ((normalized[i] - low) * 255f / range).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (stretched shl 16) or (stretched shl 8) or stretched
    }
}

/** 提升饱和度与对比度 */
private fun colorBoost(pixels: IntArray) {
    for (i in pixels.indices) {
        val p = pixels[i]
        var r = (p shr 16) and 0xFF
        var g = (p shr 8) and 0xFF
        var b = p and 0xFF
        val luma = (0.299f * r + 0.587f * g + 0.114f * b)
        r = (luma + (r - luma) * 1.35f).toInt().coerceIn(0, 255)
        g = (luma + (g - luma) * 1.35f).toInt().coerceIn(0, 255)
        b = (luma + (b - luma) * 1.35f).toInt().coerceIn(0, 255)
        // 轻微提亮
        r = min(255, (r * 1.05f).toInt())
        g = min(255, (g * 1.05f).toInt())
        b = min(255, (b * 1.05f).toInt())
        pixels[i] = Color.argb(0xFF, r, g, b)
    }
}

/** Otsu 全局阈值 */
fun otsuThreshold(pixels: IntArray): Int {
    val histogram = IntArray(256)
    for (p in pixels) {
        val g = (((p shr 16) and 0xFF) * 0.299f +
            ((p shr 8) and 0xFF) * 0.587f +
            (p and 0xFF) * 0.114f).toInt().coerceIn(0, 255)
        histogram[g]++
    }
    val total = pixels.size
    var sum = 0f
    for (v in 0..255) sum += v * histogram[v]

    var sumB = 0f
    var wB = 0
    var maxVariance = 0f
    var threshold = 128

    for (v in 0..255) {
        wB += histogram[v]
        if (wB == 0) continue
        val wF = total - wB
        if (wF == 0) break
        sumB += v * histogram[v]
        val mB = sumB / wB
        val mF = (sum - sumB) / wF
        val variance = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
        if (variance > maxVariance) {
            maxVariance = variance
            threshold = v
        }
    }
    return threshold
}
