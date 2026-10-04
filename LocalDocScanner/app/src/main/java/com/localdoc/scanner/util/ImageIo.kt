package com.localdoc.scanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 图片读写：统一处理采样缩放与 EXIF 方向，避免 OOM 与"照片躺倒" */
object ImageIo {

    fun loadFromUri(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        require(maxSide > 0)
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                })
            } ?: return null
            fixOrientation(context, uri, bitmap)
        }.getOrNull()
    }

    fun loadFromFile(file: File, maxSide: Int = 2048): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val sample = sampleSize(opts.outWidth, opts.outHeight, maxSide)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orient(bitmap, orientation)
    }

    fun decodeBytes(bytes: ByteArray, maxSide: Int = 2048): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val sample = sampleSize(opts.outWidth, opts.outHeight, maxSide)
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(maxSide > 0) { "图片尺寸必须大于0" }
        var sample = 1
        val max = maxOf(width, height)
        while (max / sample > maxSide) sample *= 2
        return sample
    }

    private fun fixOrientation(context: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orient(bmp, orientation)
    }

    private fun orient(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                else -> return bmp
            }
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also { if (it !== bmp) bmp.recycle() }
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bmp
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    /** 小角度纠偏后裁掉旋转产生的空白边，原图对象保持不变。 */
    fun rotateCropped(bmp: Bitmap, degrees: Float): Bitmap {
        val angle = degrees.coerceIn(-15f, 15f)
        if (abs(angle) < 0.01f) return bmp
        val rotated = rotate(bmp, angle)
        val radians = Math.toRadians(abs(angle).toDouble())
        val sine = sin(radians)
        val cosine = cos(radians)
        val sourceW = bmp.width.toDouble()
        val sourceH = bmp.height.toDouble()
        val denominator = (cosine * cosine - sine * sine).coerceAtLeast(0.001)
        var cropW = ((sourceW * cosine - sourceH * sine) / denominator).toInt()
        var cropH = ((sourceH * cosine - sourceW * sine) / denominator).toInt()
        if (cropW <= 0 || cropH <= 0) {
            val scale = (cosine + sine).coerceAtLeast(1.0)
            cropW = (sourceW / scale).toInt()
            cropH = (sourceH / scale).toInt()
        }
        cropW = cropW.coerceIn(1, rotated.width)
        cropH = cropH.coerceIn(1, rotated.height)
        val result = Bitmap.createBitmap(
            rotated,
            (rotated.width - cropW) / 2,
            (rotated.height - cropH) / 2,
            cropW,
            cropH
        )
        if (result !== rotated && rotated !== bmp) rotated.recycle()
        return result
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92): Boolean {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.part")
        return try {
            FileOutputStream(temporary).use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it)) { "JPEG编码失败" }
                it.flush(); it.fd.sync()
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { temporary.delete() }
    }

    fun copyBytes(context: Context, uri: Uri, dest: File, maxBytes: Long = 512L * 1024 * 1024): Boolean = try {
        require(maxBytes > 0)
        AtomicFiles.write(dest) { staged ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= maxBytes) { "单文件超过导入上限512MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取文件")
        }
        true
    } catch (e: Exception) { false }
}
