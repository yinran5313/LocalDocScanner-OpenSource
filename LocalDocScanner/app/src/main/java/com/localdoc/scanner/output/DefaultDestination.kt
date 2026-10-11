package com.localdoc.scanner.output

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localdoc.scanner.data.AppPreferences
import java.io.File

/** Explicit save to a user-authorized SAF directory; never overwrites an existing file. */
object DefaultDestination {
    fun save(context: Context, file: File): String {
        val value = AppPreferences(context).text("destination")
        require(value.isNotBlank()) { "请先在设置中选择默认保存文件夹" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(value))
        require(root != null && root.exists() && root.isDirectory && root.canWrite()) { "默认文件夹授权已失效，请在设置中重新选择" }
        var name = file.name
        var suffix = 1
        while (root.findFile(name) != null) { name = "${file.nameWithoutExtension} (${suffix++}).${file.extension}" }
        val target = root.createFile(OutputHistoryStore.mimeFor(file), name) ?: error("无法在默认文件夹创建文件")
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法写入文件夹")
            val label = OutputHistoryStore.describeDestination(context, target.uri)
            OutputHistoryStore.markSaved(context, file, target.uri, label)
            return label
        } catch (e: Exception) { target.delete(); throw e }
    }
}
