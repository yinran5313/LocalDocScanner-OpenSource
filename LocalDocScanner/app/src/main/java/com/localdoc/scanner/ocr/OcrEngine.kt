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
 * 只使用内置 PP-OCRv6 medium；旧任务的 precise 参数保留兼容。
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
        val mutex = com.localdoc.scanner.models.ModelInference.gate
        var mediumEngine: PaddleOCR? = null
    }

    override val available: Boolean = true
    override val label: String = "PP-OCRv6 medium 本地离线"

    override suspend fun recognize(bitmap: Bitmap, precise: Boolean): OcrOutcome = mutex.withLock {
        check(OpenCVUtils.init(app)) {
            "OpenCV 初始化失败：${OpenCVUtils.lastError ?: "设备无法加载图像处理库"}"
        }
        val engine = mediumEngine ?: create().also { mediumEngine = it }
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

    private suspend fun create(): PaddleOCR = PaddleOCR.create(
        context = app,
        config = PaddleOCRConfig(
            detLimitSideLen = 96,
            detMaxSideLimit = 4000,
            detBoxThresh = 0.52f,
            recScoreThresh = 0.35f,
            recBatchSize = 2
        ),
        engineConfig = EngineConfig(numThreads = 4),
        detModelAssetPath = "models/medium/det/inference.onnx",
        recModelAssetPath = "models/medium/rec/inference.onnx",
        recConfigAssetPath = "models/medium/rec/inference.yml"
    )

    override suspend fun close() = mutex.withLock {
        mediumEngine?.release()
        mediumEngine = null
    }
}
