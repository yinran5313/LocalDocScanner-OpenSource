package com.localdoc.scanner.pdf

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

fun printPdf(context: Context, file: File, title: String) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print(title, PdfFilePrintAdapter(file, title), PrintAttributes.Builder().build())
}

private class PdfFilePrintAdapter(private val file: File, private val title: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
        } else {
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(title)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build(),
                oldAttributes != newAttributes
            )
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        runCatching {
            file.inputStream().use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
        }.onSuccess {
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }.onFailure { callback.onWriteFailed(it.message) }
    }
}
