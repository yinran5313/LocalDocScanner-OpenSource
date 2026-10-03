package com.localdoc.scanner.cv

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Bounded Android adapter to Leptonica dewarpSinglePage; leaves unreliable pages unchanged. */
object BookDewarp {
    data class Result(val bitmap: Bitmap, val applied: Boolean, val note: String)
    private val available by lazy { runCatching { System.loadLibrary("scanner_dewarp") }.isSuccess }
    private external fun flattenNative(source: Bitmap, output: Bitmap): Int

    @Synchronized
    fun flatten(source: Bitmap): Result {
        val scale = (2200f / maxOf(source.width, source.height)).coerceAtMost(1f)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1), true) else source
        val input = if (sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, false)
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        try {
            val status = if (available) flattenNative(input, output) else -1
            if (status == 1) return Result(output, true, "已按文字行曲率展平，请检查边缘和小字")
            output.recycle()
            return Result(source.copy(Bitmap.Config.ARGB_8888, false), false,
                if (available) "未找到可靠的文字行模型，保留原页" else "展平组件不可用，保留原页")
        } catch (e: Throwable) {
            output.recycle()
            throw e
        } finally {
            if (input !== sized) input.recycle()
            if (sized !== source) sized.recycle()
        }
    }
}
