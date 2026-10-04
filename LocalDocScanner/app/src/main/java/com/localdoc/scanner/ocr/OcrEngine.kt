package com.localdoc.scanner.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OCR 引擎抽象。
 *
 * V3 内置 PP-OCRv6 tiny（普通档）与 medium（高精度档），使用 ONNX Runtime 本地推理。
 */
data class OcrOutcome(
    val text: String,
    val lineCount: Int,
    val totalTimeMs: Long,
    val coldLoadTimeMs: Long,
    val boxes: List<OcrTextBox> = emptyList()
)

interface OcrEngine {
    val available: Boolean
    val label: String
    suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome
    suspend fun recognize(bitmap: Bitmap, precise: Boolean, language: OcrLanguage): OcrOutcome = recognize(bitmap, language.medium(precise))
    suspend fun close() = Unit
}

class PaddleOcrEngine(context: Context) : OcrEngine {
    private val app = context.applicationContext
    // UI tools and background jobs share one model pool and one inference lock.
    // Closing a client releases the pool under that lock; later calls reload it.
    private companion object {
        val mutex = Mutex()
        var fast: PaddleOCR? = null
        var preciseEngine: PaddleOCR? = null
    }

    override val available: Boolean = true
    override val label: String = "PP-OCRv6 本地离线"

    override suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome = mutex.withLock {
        check(OpenCVUtils.init(app)) {
            "OpenCV 初始化失败：${OpenCVUtils.lastError ?: "设备无法加载图像处理库"}"
        }
        val engine = if (precise) {
            fast?.release(); fast = null
            preciseEngine ?: create("medium", precise = true).also { preciseEngine = it }
        } else {
            preciseEngine?.release(); preciseEngine = null
            fast ?: create("tiny", precise = false).also { fast = it }
        }
        val result = engine.recognize(bitmap)
        OcrOutcome(
            text = result.results.joinToString("\n") { it.text.trim() }.trim(),
            lineCount = result.lineCount,
            totalTimeMs = result.totalTimeMs,
            coldLoadTimeMs = result.coldLoadTimeMs,
            boxes = result.results.mapNotNull { line ->
                val points = line.box.points
                OcrTextBox(line.text.trim(),
                    (points.minOf { it.x } / bitmap.width).coerceIn(0f, 1f),
                    (points.minOf { it.y } / bitmap.height).coerceIn(0f, 1f),
                    (points.maxOf { it.x } / bitmap.width).coerceIn(0f, 1f),
                    (points.maxOf { it.y } / bitmap.height).coerceIn(0f, 1f)).takeIf(OcrLayout::valid)
            }
        )
    }

    private suspend fun create(model: String, precise: Boolean): PaddleOCR = PaddleOCR.create(
        context = app,
        config = PaddleOCRConfig(
            detLimitSideLen = if (precise) 96 else 64,
            detMaxSideLimit = if (precise) 4000 else 2600,
            detBoxThresh = if (precise) 0.52f else 0.60f,
            recScoreThresh = if (precise) 0.35f else 0.45f,
            recBatchSize = if (precise) 2 else 1
        ),
        engineConfig = EngineConfig(numThreads = if (precise) 4 else 3),
        detModelAssetPath = "models/$model/det/inference.onnx",
        recModelAssetPath = "models/$model/rec/inference.onnx",
        recConfigAssetPath = "models/$model/rec/inference.yml"
    )

    override suspend fun close() = mutex.withLock {
        fast?.release()
        preciseEngine?.release()
        fast = null
        preciseEngine = null
    }
}
