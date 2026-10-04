package com.localdoc.scanner.jobs

import android.content.Context
import androidx.work.*
import com.localdoc.scanner.data.ToolDrafts
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ToolTasks {
    private val submitLock = Mutex()
    private val filesLock = Any()
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private val secrets = ConcurrentHashMap<String, CharArray>()
    val gson get() = ToolDrafts.gson
    fun root(context: Context) = File(context.filesDir, "tool-jobs")
    fun dir(context: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(root(context), id)
    }
    private fun name(id: String) = "tool-task:$id"
    fun spec(context: Context, id: String): ToolTaskSpec = gson.fromJson(File(dir(context, id), "spec.json").readText(), ToolTaskSpec::class.java)
    fun status(context: Context, id: String): ToolTaskStatus? = synchronized(filesLock) { runCatching {
        gson.fromJson(File(dir(context, id), "status.json").readText(), ToolTaskStatus::class.java)
    }.getOrNull() }
    fun outcome(context: Context, id: String): FlowOutcome? = runCatching {
        gson.fromJson(File(dir(context, id), "result.json").readText(), FlowOutcome::class.java)
    }.getOrNull()
    fun recoveredOutcome(context: Context, id: String): FlowOutcome {
        val root = dir(context, id)
        val checkpoints = ToolCheckpoints(root, gson)
        val receipts = File(root, "units").listFiles().orEmpty().mapNotNull { file -> runCatching {
            val receipt = gson.fromJson(file.readText(), ToolUnitReceipt::class.java)
            checkpoints.load(receipt.key)
        }.getOrNull() }
        val files = receipts.flatMap { it.files }.map { File(it.path) }.distinctBy { it.absolutePath }.toMutableList()
        val text = receipts.filter { it.key.startsWith("ocr:") }.sortedWith(compareBy(
            { it.key.substringAfter(':').substringBefore(':').toIntOrNull() ?: 0 },
            { it.key.substringAfterLast(':').toIntOrNull() ?: 0 }
        )).mapNotNull { runCatching { com.google.gson.JsonParser.parseString(it.payload).asJsonObject.get("text").asString }.getOrNull() }.joinToString("\n\n")
        if (text.isNotBlank()) {
            val target = File(root, "outputs/已完成步骤的识别文字.txt")
            AtomicFiles.text(target, text); files += target
        }
        files.forEach { com.localdoc.scanner.output.OutputHistoryStore.recordGenerated(context, it,
            com.localdoc.scanner.output.OutputHistoryStore.mimeFor(it)) }
        return FlowOutcome(listOf("结果" to "仅包含已完成步骤的产物，任务尚未完整完成"), files,
            copyText = text.takeIf { it.isNotBlank() })
    }
    fun all(context: Context): List<ToolTaskStatus> = root(context).listFiles().orEmpty().mapNotNull { status(context, it.name) }.sortedByDescending { it.updatedAt }
    fun update(context: Context, value: ToolTaskStatus, worker: Boolean = false) = synchronized(filesLock) {
        // A cancellation must not race the worker into overwriting the user's pause.
        if (worker && status(context, value.id)?.state == ToolTaskState.PAUSED) return@synchronized
        AtomicFiles.text(File(dir(context, value.id), "status.json"), gson.toJson(value.copy(updatedAt = System.currentTimeMillis())))
        updates.tryEmit(Unit)
    }
    fun watch(context: Context): Flow<List<ToolTaskStatus>> = merge(
        updates.onStart { emit(Unit) },
        WorkManager.getInstance(context).getWorkInfosByTagFlow("scanner-tools").map { Unit }
    ).map { withContext(Dispatchers.IO) { reconcile(context); all(context) } }.distinctUntilChanged()

    private fun reconcile(context: Context) {
        val work = WorkManager.getInstance(context).getWorkInfosByTag("scanner-tools").get()
        all(context).filter { it.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING) }.forEach { task ->
            val found = work.filter { name(task.id) in it.tags }
            if ((found.isEmpty() && System.currentTimeMillis() - task.updatedAt > 30_000) || found.isNotEmpty() && found.all { it.state.isFinished }) {
                update(context, task.copy(state = ToolTaskState.PAUSED, label = "任务已中断，可以续跑"))
            }
        }
    }
    fun latest(context: Context, request: ToolRequest, action: String = "process"): String? =
        context.getSharedPreferences("tool_task_links", Context.MODE_PRIVATE).getString(link(request, action), null)
    private fun link(request: ToolRequest, action: String) = ToolCheckpoints.digest("${ToolDrafts.key(request)}:$action".toByteArray())
    fun select(context: Context, specification: ToolTaskSpec) {
        context.getSharedPreferences("tool_task_links", Context.MODE_PRIVATE).edit()
            .putString(link(specification.request, specification.action), specification.id).commit()
    }

    suspend fun submit(context: Context, request: ToolRequest, parameters: Map<String, String>, action: String = "process",
        password: CharArray? = null): String = withContext(Dispatchers.IO) { submitLock.withLock {
        val previous = latest(context, request, action)?.let { status(context, it) }
        require(previous?.state !in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)) { "已有任务正在处理，请先暂停" }
        val id = UUID.randomUUID().toString()
        val hashes = request.files.associate { file -> require(file.isFile) { "输入文件缺失：${file.name}" }; file.absolutePath to ToolCheckpoints.hash(file) }.toMutableMap()
        // Do not serialize secrets, even if a future caller mistakenly passes one in configuration.
        require(parameters.keys.none { it.contains("password", true) || it.contains("secret", true) }) { "密码只能通过内存交接" }
        // URI grants alone are not immutable. Stage edit images/certificates before enqueueing.
        var asset = 0
        fun copyUri(raw: String): String {
            val uri = android.net.Uri.parse(raw)
            val target = File(dir(context, id), "assets/${asset++}.bin")
            AtomicFiles.write(target) { staged ->
                context.contentResolver.openInputStream(uri)?.use { input -> staged.outputStream().use { out -> input.copyTo(out) } }
                    ?: error("无法读取附加文件，请重新选择")
            }
            hashes[target.absolutePath] = ToolCheckpoints.hash(target)
            return android.net.Uri.fromFile(target).toString()
        }
        fun stage(value: com.google.gson.JsonElement) {
            if (value.isJsonArray) value.asJsonArray.forEach(::stage)
            else if (value.isJsonObject) value.asJsonObject.entrySet().toList().forEach { (key, child) ->
                if (key in setOf("signatureUri", "watermarkUri") && !child.isJsonNull) value.asJsonObject.addProperty(key, copyUri(child.asString))
                else stage(child)
            }
        }
        val frozen = parameters.mapValues { (key, json) ->
            val value = com.google.gson.JsonParser.parseString(json)
            if (key == "signingStoreUri" && !value.isJsonNull) gson.toJson(copyUri(value.asString))
            else { if (key == "edits") stage(value); value.toString() }
        }
        val specification = ToolTaskSpec(id, request, frozen, hashes, action, password != null)
        AtomicFiles.text(File(dir(context, id), "spec.json"), gson.toJson(specification))
        update(context, ToolTaskStatus(id))
        password?.let { secrets[id] = it.copyOf() }
        context.getSharedPreferences("tool_task_links", Context.MODE_PRIVATE).edit().putString(link(request, action), id).commit()
        enqueue(context, id)
        id
    } }
    suspend fun resume(context: Context, id: String, password: CharArray? = null) = withContext(Dispatchers.IO) { submitLock.withLock {
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork(name(id)).get()
        require(work.none { !it.state.isFinished }) { "任务仍在停止或运行，请稍后续跑" }
        val specification = spec(context, id)
        if (specification.needsPassword) {
            require(password != null && (specification.request.tool.id != "pdf_encrypt" || password.isNotEmpty())) { "请重新输入密码，再点续跑" }
            secrets.put(id, password.copyOf())?.fill('\u0000')
        }
        update(context, (status(context, id) ?: ToolTaskStatus(id)).copy(state = ToolTaskState.QUEUED, error = "", label = "准备校验并续跑"))
        enqueue(context, id)
    } }
    suspend fun pause(context: Context, id: String) = withContext(Dispatchers.IO) {
        val previous = status(context, id) ?: return@withContext
        if (previous.state !in setOf(ToolTaskState.RUNNING, ToolTaskState.QUEUED)) return@withContext
        update(context, previous.copy(state = ToolTaskState.PAUSED, label = "已暂停，已完成单元保留"))
        WorkManager.getInstance(context).cancelUniqueWork(name(id)).result.get()
        secrets.remove(id)?.fill('\u0000')
    }
    internal fun secret(id: String) = secrets.remove(id)
    private fun enqueue(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<ToolTaskWorker>().setInputData(workDataOf("task" to id))
            .addTag("scanner-tools").addTag(name(id))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, request).result.get()
    }
}
