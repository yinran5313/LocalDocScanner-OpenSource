package com.localdoc.scanner.util

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** 系统分享：走 FileProvider，不给外部任何持久化授权 */
object Share {

    fun file(context: Context, file: File, mime: String) = files(context, listOf(file), mime)

    fun files(context: Context, files: List<File>, mime: String) {
        val uris = ArrayList<android.net.Uri>()
        files.forEach { f ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }.onSuccess { uris.add(it) }
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, "分享文件", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        context.startActivity(
            Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun uri(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "分享文件", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun open(context: Context, file: File, mime: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        openUri(context, uri, mime)
    }.getOrDefault(false)

    fun openUri(context: Context, uri: Uri, mime: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(context.contentResolver, "打开文件", uri)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
