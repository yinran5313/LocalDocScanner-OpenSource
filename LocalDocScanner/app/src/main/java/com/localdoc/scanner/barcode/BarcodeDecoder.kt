package com.localdoc.scanner.barcode

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/** 条码 / 二维码识别：ZXing core 是纯 Java，无 so，完全离线 */
object BarcodeDecoder {

    fun decode(source: Bitmap): List<Result> {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val results = mutableListOf<Result>()
        // 正常一次，反色一次（对付深色底白码）
        results += attempt(pixels, w, h, invert = false)
        if (results.isEmpty()) {
            val inverted = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                inverted[i] = (p and 0xFF000000.toInt()) or (0x00FFFFFF - (p and 0x00FFFFFF))
            }
            results += attempt(inverted, w, h, invert = true)
        }
        return results
    }

    private fun attempt(pixels: IntArray, w: Int, h: Int, invert: Boolean): List<Result> {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }
        return try {
            val src = RGBLum(pixels, w, h)
            val bitmap = BinaryBitmap(HybridBinarizer(src))
            listOf(MultiFormatReader().decode(bitmap, hints))
        } catch (e: NotFoundException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** 直接吃 ARGB_8888 像素数组的亮度源，避免先把 Bitmap 拷成字节数组 */
private class RGBLum(
    private val pixels: IntArray,
    private val w: Int,
    private val h: Int
) : com.google.zxing.LuminanceSource(w, h) {

    private val lum: ByteArray = ByteArray(w * h).also { out ->
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val target = row?.takeIf { it.size >= w } ?: ByteArray(w)
        System.arraycopy(lum, y * w, target, 0, w)
        return target
    }

    override fun getMatrix(): ByteArray = lum

    override fun isCropSupported(): Boolean = false

    override fun isRotateSupported(): Boolean = false
}
