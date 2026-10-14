package com.localdoc.scanner.pdf

import android.content.Context
import android.graphics.*
import com.localdoc.scanner.util.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedSignature(val id: String, val name: String, val file: File)
object SignatureStore {
    private fun root(context: Context) = File(context.filesDir, "signature-library").apply { mkdirs() }
    fun all(context: Context): List<SavedSignature> = root(context).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { manifest ->
        runCatching { val json = JSONObject(manifest.readText()); val id = manifest.nameWithoutExtension
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val image = File(root(context), "$id.png"); require(image.isFile)
            SavedSignature(id, json.getString("name"), image)
        }.getOrNull()
    }.sortedByDescending { it.file.lastModified() }
    fun save(context: Context, name: String, strokes: List<List<PdfInkPoint>>, aspect: Float = 2.4f): SavedSignature {
        require(strokes.any { it.size >= 2 } && strokes.sumOf { it.size } <= 50000 && aspect.isFinite() && aspect in .5f..8f) { "请先手写签名" }
        require(strokes.flatten().all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
        val id = UUID.randomUUID().toString(); val file = File(root(context), "$id.png")
        val bitmap = Bitmap.createBitmap(1200, (1200 / aspect).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
            strokes.filter { it.size >= 2 }.forEach { stroke -> val path = Path().apply { moveTo(stroke.first().x * bitmap.width, stroke.first().y * bitmap.height); stroke.drop(1).forEach { lineTo(it.x * bitmap.width, it.y * bitmap.height) } }; canvas.drawPath(path, paint) }
            AtomicFiles.write(file) { it.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } }
            val title = name.trim().take(80).ifBlank { "我的签名" }
            AtomicFiles.text(File(root(context), "$id.json"), JSONObject().put("name", title).put("aspect", aspect.toDouble()).put("strokes", JSONArray(strokes.map { line -> line.map { listOf(it.x, it.y) } })).toString())
            return SavedSignature(id, title, file)
        } catch (e: Exception) { file.delete(); throw e } finally { bitmap.recycle() }
    }
    fun remove(context: Context, signature: SavedSignature) {
        require(signature.id.matches(Regex("[a-zA-Z0-9-]+")))
        val dir = root(context).canonicalFile
        val file = File(dir, "${signature.id}.png").canonicalFile
        require(file.parentFile == dir)
        check(!file.exists() || file.delete()) { "签名暂时无法删除" }
        File(dir, "${signature.id}.json").delete()
    }

    /** Restore only a checked PNG; signing certificates/private keys are never involved. */
    internal fun importSaved(context: Context, name: String, source: File): SavedSignature {
        require(source.length() in 1..16L*1024*1024) { "备份签名图片大小无效" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.absolutePath,bounds)
        require(bounds.outMimeType=="image/png" && bounds.outWidth in 1..4000 && bounds.outHeight in 1..4000 && bounds.outWidth.toLong()*bounds.outHeight<=12_000_000) { "备份签名图片格式或尺寸无效" }
        val id=UUID.randomUUID().toString(); val image=File(root(context),"$id.png")
        try {
            AtomicFiles.copy(source,image)
            val title=name.trim().take(80).ifBlank { "恢复的签名" }
            AtomicFiles.text(File(root(context),"$id.json"),JSONObject().put("name",title).put("aspect",bounds.outWidth.toDouble()/bounds.outHeight).toString())
            return SavedSignature(id,title,image)
        } catch(e: Exception) { image.delete(); throw e }
    }
}
