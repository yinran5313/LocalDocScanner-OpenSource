package com.localdoc.scanner.jobs

import android.net.Uri
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.pdf.*
import java.io.File

data class FlowOutcome(val summary: List<Pair<String, String>>, val files: List<File>,
    val docFiles: List<File> = emptyList(), val copyText: String? = null,
    val reviewPages: List<ReviewPage> = emptyList(), val failures: List<String> = emptyList(),
    val sensitiveRegions: List<SensitiveRegion> = emptyList())
data class ReviewPage(val source: String, val page: Int, val raw: String,
    val fields: Map<String, String>, val rows: List<List<String>>, val error: String = "", val reviewed: Boolean = false, val edited: Boolean = false)
data class PendingPdfEdit(val operation: Int, val label: String, val pageIndex: Int,
    val text: String, val header: String, val footer: String, val watermark: String,
    val addPageNumbers: Boolean, val opacity: Float, val x: Float, val y: Float,
    val width: Float, val height: Float, val markup: PdfMarkup, val strokes: List<List<PdfInkPoint>>,
    val signatureUri: Uri?, val watermarkUri: Uri?, val formValues: Map<String, String>,
    val annotationChange: PdfAnnotationChange? = null, val decoration: PdfDecorationOptions? = null,
    val textChange: PdfTextChange? = null)

enum class ToolTaskState { QUEUED, RUNNING, PAUSED, WAITING_PASSWORD, FAILED, PARTIAL, SUCCEEDED }
data class ToolTaskSpec(val id: String, val request: ToolRequest, val parameters: Map<String, String>,
    val inputHashes: Map<String, String>, val action: String = "process", val needsPassword: Boolean = false,
    val createdAt: Long = System.currentTimeMillis())
data class ToolTaskStatus(val id: String, val state: ToolTaskState = ToolTaskState.QUEUED,
    val completed: Int = 0, val total: Int = 0, val label: String = "等待后台处理",
    val error: String = "", val updatedAt: Long = System.currentTimeMillis())
data class ToolFileReceipt(val path: String, val hash: String)
data class ToolUnitReceipt(val key: String, val payload: String, val files: List<ToolFileReceipt>)
