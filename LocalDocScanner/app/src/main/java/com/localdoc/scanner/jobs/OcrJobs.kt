package com.localdoc.scanner.jobs

import android.content.Context
import androidx.work.*
import com.localdoc.scanner.data.DocRepository
import com.localdoc.scanner.data.db.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object OcrJobs {
    fun root(context: Context) = File(context.filesDir, "ocr-jobs")
    fun name(docId: String) = "document-ocr:$docId"
    fun observe(context: Context, docId: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(docId))
    fun recipe(page: PageEntity) = listOf(page.width, page.height, page.quarterTurns, page.cropPoints,
        page.filter, page.brightness, page.contrast, page.fineRotation).joinToString("|")
    suspend fun start(context: Context, docId: String, precise: Boolean, resume: Boolean = false): String = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val works = manager.getWorkInfosForUniqueWork(name(docId)).get()
        require(works.none { !it.state.isFinished }) { "这份文档已有OCR任务，请先暂停或等它完成" }
        val pages = DocRepository(context).pages(docId)
        require(pages.isNotEmpty()) { "文档没有可识别页面" }
        val previousId = context.getSharedPreferences("ocr_job_receipts", Context.MODE_PRIVATE).getString(docId, null)
        val previous = if (resume && previousId != null) runCatching { OcrCheckpointStore.load(root(context), previousId) }.getOrNull() else null
        require(!resume || previous != null && previous.precise == precise) { "没有同档位的恢复记录，请开始新任务" }
        val id = previous?.id ?: UUID.randomUUID().toString()
        val checkpoints = pages.map { page ->
            val hash = OcrCheckpointStore.hash(File(page.filePath)); val recipe = recipe(page)
            previous?.pages?.firstOrNull { it.id == page.id }?.takeIf {
                OcrCheckpointStore.canReuse(it, hash, recipe, page.ocrUpdatedAt)
            } ?: OcrPageCheckpoint(page.id, hash, recipe)
        }
        OcrCheckpointStore.save(root(context), OcrCheckpoint(id, docId, precise, checkpoints, previous?.createdAt ?: System.currentTimeMillis()))
        context.getSharedPreferences("ocr_job_receipts", Context.MODE_PRIVATE).edit().putString(docId, id).commit()
        val request = OneTimeWorkRequestBuilder<DocumentOcrWorker>().setInputData(workDataOf("journal" to id))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS).build()
        context.getSharedPreferences("ocr_job_receipts", Context.MODE_PRIVATE).edit().putString("$docId:work", request.id.toString()).commit()
        context.getSharedPreferences("ocr_job_receipts", Context.MODE_PRIVATE).edit().putBoolean("$docId:precise", precise).commit()
        manager.enqueueUniqueWork(name(docId), ExistingWorkPolicy.KEEP, request).result.get()
        id
    }
    fun cancel(context: Context, docId: String) { WorkManager.getInstance(context).cancelUniqueWork(name(docId)) }
}
