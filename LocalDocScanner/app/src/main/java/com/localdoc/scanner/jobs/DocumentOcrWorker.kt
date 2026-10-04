package com.localdoc.scanner.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.localdoc.scanner.data.DocRepository
import com.localdoc.scanner.data.db.AppDatabase
import com.localdoc.scanner.ocr.OcrLayout
import com.localdoc.scanner.ocr.PaddleOcrEngine
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext as currentCoroutineContext

/** WorkManager persists scheduling; journal persists completed page hashes. */
class DocumentOcrWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    internal var recognizerForTest: com.localdoc.scanner.ocr.OcrEngine? = null
    override suspend fun doWork(): Result = execution.withLock { withContext(Dispatchers.IO) { runJob() } }
    private suspend fun runJob(): Result {
        val id = inputData.getString("journal") ?: return Result.failure()
        val root = OcrJobs.root(applicationContext)
        var job = runCatching { OcrCheckpointStore.load(root, id) }.getOrElse { return Result.failure(workDataOf("error" to "任务清单无法读取")) }
        val dao = AppDatabase.get(applicationContext).docDao()
        val repo = DocRepository(applicationContext)
        val started = android.os.SystemClock.elapsedRealtime()
        val engine = recognizerForTest ?: PaddleOcrEngine(applicationContext)
        try {
            for ((index, saved) in job.pages.withIndex()) {
                currentCoroutineContext.ensureActive()
                val page = dao.getPage(saved.id)
                var receipt = saved
                try {
                    check(dao.getDoc(job.docId)?.deleted == false && page != null && !page.deleted && page.docId == job.docId) { "文档或页面已移除" }
                    val file = File(page.filePath)
                    val hash = OcrCheckpointStore.hash(file)
                    check(hash == saved.hash && OcrJobs.recipe(page) == saved.recipe) { "页面已经编辑，请重新提交该文档" }
                    if (OcrCheckpointStore.canReuse(saved, hash, saved.recipe, page.ocrUpdatedAt)) continue
                    val bitmap = ImageIo.loadFromFile(file, if (job.precise) 3600 else 2400) ?: error("页面图片无法读取")
                    val outcome = try { engine.recognize(bitmap, job.precise) } finally { bitmap.recycle() }
                    currentCoroutineContext.ensureActive()
                    check(OcrCheckpointStore.hash(file) == saved.hash) { "识别期间页面已修改" }
                    val now = System.currentTimeMillis()
                    val written = dao.setOcrIfUnchanged(page.id, page.updatedAt, now, outcome.text,
                        OcrLayout.encode(outcome.boxes), if (job.precise) "PP-OCRv6-medium" else "PP-OCRv6-tiny")
                    check(written == 1) { "识别期间页面已编辑，结果未覆盖" }
                    receipt = saved.copy(done = true, savedAt = now, error = "")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    receipt = saved.copy(done = false, savedAt = 0, error = (e.message ?: "识别失败").take(300))
                }
                job = job.copy(pages = job.pages.mapIndexed { i, value -> if (i == index) receipt else value })
                OcrCheckpointStore.save(root, job)
                repo.rebuildDocumentOcr(job.docId)
                setProgress(workDataOf("done" to job.pages.count { it.done }, "total" to job.pages.size, "failed" to job.pages.count { it.error.isNotEmpty() }))
                // Yield before WorkManager's normal execution limit; committed pages are reused.
                if (android.os.SystemClock.elapsedRealtime() - started > 240000 && index < job.pages.lastIndex) return Result.retry()
            }
            val failures = job.pages.filterNot { it.done }
            val data = workDataOf("done" to job.pages.count { it.done }, "total" to job.pages.size,
                "failed" to failures.size, "error" to failures.joinToString("；") { "${job.pages.indexOf(it) + 1}页：${it.error}" }.take(1800))
            return if (failures.isEmpty()) Result.success(data) else Result.failure(data)
        } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { engine.close() } }
    }
    companion object { private val execution = Mutex() }
}
