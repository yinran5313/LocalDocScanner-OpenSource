package com.localdoc.scanner.jobs

import android.content.Context
import androidx.work.*
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal class ToolSliceEnded : Exception()
internal class ToolPasswordNeeded : Exception("密码不会保存，请重新输入密码后续跑")

class ToolTaskWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = execution.withLock { withContext(Dispatchers.IO) {
        val id = inputData.getString("task") ?: return@withContext Result.failure()
        val app = applicationContext
        val old = ToolTasks.status(app, id) ?: return@withContext Result.failure()
        if (old.state == ToolTaskState.PAUSED) return@withContext Result.success()
        var secret: CharArray? = null
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
            val spec = ToolTasks.spec(app, id)
            ToolTasks.update(app, old.copy(state = ToolTaskState.RUNNING, label = "校验输入文件", error = ""), worker = true)
            spec.inputHashes.forEach { (path, hash) ->
                ensureActive()
                require(File(path).isFile && ToolCheckpoints.hash(File(path)) == hash) { "输入文件已更改或缺失，请重新提交：${File(path).name}" }
            }
            secret = ToolTasks.secret(id)
            if (spec.needsPassword && secret == null) throw ToolPasswordNeeded()
            val runner = ToolTaskProcessor(app, spec, secret) { done, total, label ->
                ToolTasks.update(app, ToolTaskStatus(id, ToolTaskState.RUNNING, done, total, label), worker = true)
                setProgress(workDataOf("done" to done, "total" to total, "tick" to System.nanoTime()))
            }
            val result = runner.run()
            ensureActive()
            withContext(NonCancellable) {
                result.files.forEach { OutputHistoryStore.recordGenerated(app, it, OutputHistoryStore.mimeFor(it)) }
                AtomicFiles.text(File(ToolTasks.dir(app, id), "result.json"), ToolTasks.gson.toJson(result))
                val last = ToolTasks.status(app, id) ?: old
                ToolTasks.update(app, last.copy(state = if (result.failures.isEmpty()) ToolTaskState.SUCCEEDED else ToolTaskState.PARTIAL,
                    label = if (result.failures.isEmpty()) "处理完成" else "部分完成，可重试失败项", error = result.failures.joinToString("\n")), worker = true)
            }
            Result.success()
        } catch (_: ToolSliceEnded) {
            val current = ToolTasks.status(app, id) ?: old
            ToolTasks.update(app, current.copy(state = ToolTaskState.QUEUED, label = "已保存进度，继续下一批"), worker = true)
            Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val current = ToolTasks.status(app, id) ?: old
            ToolTasks.update(app, current.copy(state = if (e is ToolPasswordNeeded) ToolTaskState.WAITING_PASSWORD else ToolTaskState.FAILED,
                label = "处理未完成", error = e.message ?: "处理失败"), worker = true)
            Result.failure()
        } finally { secret?.fill('\u0000') }
    } }
    companion object { private val execution = Mutex() }
}
