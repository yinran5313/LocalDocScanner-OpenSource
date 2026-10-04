package com.localdoc.scanner.capture

import android.content.Context
import android.graphics.Bitmap
import com.localdoc.scanner.cv.*
import com.localdoc.scanner.edit.*
import com.localdoc.scanner.util.ImageIo
import java.io.Closeable
import java.io.File

internal class PreparedCapture(val pages: List<EditResult>, val notes: List<String>, private val directory: File) : Closeable {
    override fun close() { directory.deleteRecursively() }
}

/** Produces durable draft-ready pages one shot at a time, without leaving the camera screen. */
internal object ContinuousCaptureProcessor {
    fun prepare(context: Context, shot: File, book: Boolean): PreparedCapture {
        val directory = File(context.cacheDir, "continuous-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val owned = mutableListOf<Bitmap>()
        try {
            val source = (ImageIo.loadFromFile(shot, 3200) ?: error("照片无法读取")).also { owned.add(it) }
            val notes = mutableListOf<String>()
            val pages = if (book) {
                val gutter = BookGutter.estimate(source)
                if (!gutter.confident) notes.add("书缝不明显，已按中间拆页，请在整理页检查")
                DocumentLayouts.splitBookSpread(source, splitRatio = if (gutter.confident) gutter.ratio else .5f)
                    .let { listOf(it.first, it.second) }.also { owned.addAll(it) }
            } else listOf(source)
            val results = pages.mapIndexed { index, page ->
                // A book spread is split intact. Single-page scanning can use the detected contour.
                val corners = if (book) defaultCropCorners(0f) else OpenCvDocument.detect(page)?.toList()?.map {
                    NormalizedPoint((it.x / page.width).coerceIn(0f, 1f), (it.y / page.height).coerceIn(0f, 1f))
                } ?: defaultCropCorners(0f).also { notes.add("未可靠识别四角，已保留整页，请检查裁边") }
                val raw = File(directory, "source_$index.jpg")
                check(ImageIo.saveJpeg(page, raw, 96)) { "无法保留拍摄原页" }
                val recipe = EditRecipe(corners = corners, filter = ScanFilter.AUTO)
                val rendered = renderProcessed(page, corners, recipe.filter, 0f, 1f, 3200).also { owned.add(it) }
                val file = File(directory, "rendered_$index.jpg")
                check(ImageIo.saveJpeg(rendered, file, 94)) { "无法保存扫描页" }
                EditResult(raw, file, recipe, rendered.width, rendered.height)
            }
            return PreparedCapture(results, notes, directory)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
        finally { owned.distinct().forEach { it.recycle() } }
    }
}
