package com.localdoc.scanner.pdf

import android.content.Context
import com.localdoc.scanner.ocr.PaddleOcrEngine
import java.io.File

data class SensitiveRegion(val page: Int, val label: String, val text: String, val rect: NormalizedRect)

internal object SensitiveRegionDetector {
    suspend fun detect(context: Context, file: File, page: Int, keywords: List<String>): List<SensitiveRegion> {
        val bitmap = PdfTools.renderPage(file, page, 3000) ?: error("无法读取待识别页面")
        val engine = PaddleOcrEngine(context)
        try {
            val outcome = engine.recognize(bitmap, precise = true)
            return outcome.boxes.mapNotNull { box ->
                val labels = SensitiveTextRules.kinds(box.text, keywords)
                if (labels.isEmpty() || !listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() }) null
                else {
                    // OCR boxes are normalized; include the entire line and a small safety margin.
                    val x = (box.left - .005f).coerceIn(0f, .999f)
                    val y = (box.top - .005f).coerceIn(0f, .999f)
                    val right = (box.right + .005f).coerceIn(x, 1f)
                    val bottom = (box.bottom + .005f).coerceIn(y, 1f)
                    if (right <= x || bottom <= y) null else SensitiveRegion(page, labels.joinToString("、"), box.text, NormalizedRect(x, y, right - x, bottom - y))
                }
            }
        } finally { bitmap.recycle(); engine.close() }
    }
}
