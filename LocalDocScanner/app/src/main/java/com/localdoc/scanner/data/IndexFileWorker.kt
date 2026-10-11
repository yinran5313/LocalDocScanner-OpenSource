package com.localdoc.scanner.data
import android.content.Context
import androidx.work.*
import java.io.File

/** Updating files never blocks UI, and failures don't prevent saving the original document. */
class IndexFileWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val path=inputData.getString("path") ?: return@withContext Result.failure()
        val file=File(path)
        FullTextIndex(applicationContext).use { index ->
            if(!file.isFile) { index.removeFile(path); return@withContext Result.success() }
            try { index.indexFile(file,inputData.getString("name") ?: file.name); Result.success() }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; Result.failure(workDataOf("error" to (e.message ?: "无法索引").take(500))) }
        }
    }
    companion object {
        fun schedule(context: Context,file: File,name: String=file.name) {
            if(file.extension.lowercase() !in setOf("pdf","doc","xls","ppt","docx","xlsx","pptx","txt","csv","md","json")) return
            val work=OneTimeWorkRequestBuilder<IndexFileWorker>().setInputData(workDataOf("path" to file.absolutePath,"name" to name))
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("index-file:${file.absolutePath}",ExistingWorkPolicy.REPLACE,work)
        }
    }
}
