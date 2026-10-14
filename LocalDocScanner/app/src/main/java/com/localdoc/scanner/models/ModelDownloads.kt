package com.localdoc.scanner.models

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object ModelDownloads {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun mutex(id: String) = locks.getOrPut(id) { Mutex() }
    fun name(id: String) = "model-download:$id"
    fun observe(context: Context, id: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(id))
    fun start(context: Context, id: String, wifiOnly: Boolean, source: Int = 0) {
        val pack = ModelCatalog.get(context,id)
        require(pack.files.all { it.urls.isNotEmpty() }) { "此模型目前需导入离线包" }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("id" to id, "source" to source))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, request)
    }
    fun pause(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork(name(id)) }
    suspend fun remove(context: Context, pack: ModelPack) = withContext(Dispatchers.IO) {
        pause(context, pack.id)
        ModelInference.gate.withLock { mutex(pack.id).withLock {
            val root = ModelCatalog.root(context).canonicalFile
            val dir = File(root, pack.id).canonicalFile
            require(dir.parentFile == root)
            check(!dir.exists() || dir.deleteRecursively()) { "模型文件暂时在使用，请稍后重试" }
        } }
    }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verify(dir: File, spec: ModelFile) = File(dir, spec.name).let { it.isFile && it.length() == spec.bytes && hash(it) == spec.sha256 }
    private fun commit(context: Context, pack: ModelPack, dir: File) {
        require(pack.files.all { verify(dir, it) }) { "模型包不完整或版本不匹配，请重新下载" }
        AtomicFiles.text(File(dir, "installed.json"), JSONObject().put("id", pack.id).put("version", pack.version)
            .put("checkedAt", System.currentTimeMillis()).toString())
    }
    suspend fun import(context: Context, uri: Uri): ModelPack = withContext(Dispatchers.IO) {
        // Small manifest first; only catalogued files can be imported.
        val pack = context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input).use { zip ->
            val first = zip.nextEntry ?: error("模型包为空")
            require(first.name == "model.json" && !first.isDirectory) { "请选择拾页离线模型包，首个文件应为 model.json" }
            val manifestBuffer = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) { val n = zip.read(buffer); if (n < 0) break; require(manifestBuffer.size() + n <= 8192) { "模型清单过大" }; manifestBuffer.write(buffer, 0, n) }
            val bytes = manifestBuffer.toByteArray()
            require(bytes.size <= 8192) { "模型清单过大" }
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            ModelCatalog.get(context, manifest.getString("id")).also { require(it.version == manifest.getString("version")) { "模型版本与当前应用不匹配" } }
        } } ?: error("无法读取模型包")
        mutex(pack.id).withLock {
            val dir = ModelCatalog.directory(context, pack)
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input).use { zip ->
                var count = 0
                val seen = mutableSetOf<String>()
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (count++ == 0) { require(entry.name == "model.json"); zip.closeEntry(); continue }
                    val spec = pack.files.firstOrNull { it.name == entry.name } ?: error("模型包包含未知文件")
                    require(!entry.isDirectory && seen.add(spec.name)) { "模型包包含重复文件" }
                    val temp = File(dir, spec.name + ".part")
                    var total = 0L
                    temp.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = zip.read(buffer); if (n < 0) break
                            total += n; require(total <= spec.bytes) { "模型文件大小不匹配" }; out.write(buffer, 0, n)
                        }
                    }
                    require(total == spec.bytes && hash(temp) == spec.sha256) { "模型完整性校验失败" }
                    AtomicFiles.copy(temp, File(dir, spec.name)); temp.delete(); zip.closeEntry()
                }
                require(seen.size == pack.files.size) { "模型文件缺失" }
            } } ?: error("无法读取模型包")
            commit(context, pack, dir)
        }
        pack
    }

    private class SliceFinished : Exception()
    suspend fun download(context: Context, pack: ModelPack, preferred: Int, progress: suspend (Long, Long) -> Unit) {
        val dir = ModelCatalog.directory(context, pack)
        val started = android.os.SystemClock.elapsedRealtime()
        val verified = pack.files.filter { verify(dir,it) }.map { it.name }.toSet()
        val remaining = pack.files.filter { it.name !in verified }.sumOf { spec -> spec.bytes-File(dir,spec.name+".part").length().coerceIn(0L,spec.bytes) }
        require(dir.usableSpace > remaining + 32L * 1024 * 1024) { "空间不足，请先清理可选模型或文件" }
        pack.files.forEach { spec ->
            coroutineContext.ensureActive()
            if (spec.name in verified) return@forEach
            val part = File(dir, spec.name + ".part")
            var success = false
            for (source in spec.urls.indices.sortedBy { if (it == preferred) 0 else 1 }) {
                coroutineContext.ensureActive()
                for (attempt in 0..1) { try {
                    if (part.length() > spec.bytes) part.delete()
                    var offset = part.length()
                    if (offset < spec.bytes) {
                        val connection = connect(spec.urls[source], offset)
                        try {
                            val response = connection.responseCode
                            require(response == 200 || response == 206) { "当前下载源暂不可用" }
                            val retains=DownloadRanges.retainsPrefix(response,offset,spec.bytes,connection.getHeaderField("Content-Range").orEmpty())
                            if (offset>0 && !retains) { offset=0; part.delete() }
                            RandomAccessFile(part, "rw").use { out ->
                                out.seek(offset)
                                connection.inputStream.buffered().use { input ->
                                    val buffer = ByteArray(64 * 1024); var last = 0L
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        if (offset == spec.bytes) break
                                        val n = input.read(buffer,0,minOf(buffer.size.toLong(),spec.bytes-offset).toInt()); if (n < 0) break
                                        offset += n; require(offset <= spec.bytes) { "模型版本或文件大小不匹配" }; out.write(buffer, 0, n)
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - last > 500) {
                                            progress(pack.files.sumOf { f -> if (f.name == spec.name) offset else minOf(File(dir, f.name).length(), f.bytes) }, pack.bytes); last = now
                                        }
                                        if (now - started > 180000) throw SliceFinished()
                                    }
                                }
                            }
                        } finally { connection.disconnect() }
                    }
                    require(part.length() == spec.bytes && hash(part) == spec.sha256) { "模型完整性校验失败" }
                    AtomicFiles.copy(part, File(dir, spec.name)); part.delete(); success = true; break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e is SliceFinished) throw e
                    android.util.Log.w("ShiyeModelDownload", "source=$source file=${spec.name} partial=${part.length()} error=${e.javaClass.simpleName}: ${e.message}")
                    if (part.length() >= spec.bytes || e.message?.contains("文件范围") == true) part.delete()
                } }
                if (success) break
            }
            check(success) { "下载暂未完成，进度已保留。可切换下载源、稍后重试或导入离线模型包。" }
        }
        commit(context, pack, dir)
        progress(pack.bytes, pack.bytes)
    }
    private fun connect(initial: String, offset: Long): HttpURLConnection {
        var url = URL(initial)
        repeat(8) {
            require(url.protocol == "https" && (url.host == "modelscope.cn" || url.host.endsWith(".modelscope.cn") || url.host.endsWith(".bcebos.com"))) { "此下载地址不在支持的下载源中" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("User-Agent", "Shiye/5.1 Android ModelDownload"); setRequestProperty("Accept-Encoding", "identity"); if (offset>0) setRequestProperty("Range", "bytes=$offset-")
            }
            if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
            val target = connection.getHeaderField("Location") ?: error("下载地址暂不可用")
            connection.disconnect(); url = URL(url, target)
        }
        error("下载源暂不可用，请稍后重试")
    }
    fun isSliceFinished(e: Exception) = e is SliceFinished
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        try {
            val pack = ModelCatalog.get(applicationContext, id)
            ModelDownloads.mutex(id).withLock {
                ModelDownloads.download(applicationContext, pack, inputData.getInt("source", 0)) { done, total -> setProgress(workDataOf("done" to done, "total" to total)) }
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (ModelDownloads.isSliceFinished(e)) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "下载失败，请稍后重试")))
        }
    }
}
