package com.localdoc.scanner.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class ModelFile(val name: String, val bytes: Long, val sha256: String, val urls: List<String>)
data class ModelPack(val id: String, val title: String, val engine: String, val description: String,
    val version: String, val license: String, val source: String, val files: List<ModelFile>) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

object ModelCatalog {
    @Volatile private var cached: List<ModelPack>? = null
    fun all(context: Context): List<ModelPack> = cached ?: synchronized(this) {
        cached ?: Gson().fromJson<List<ModelPack>>(context.assets.open("model_catalog.json").bufferedReader().use { it.readText() },
            object : TypeToken<List<ModelPack>>() {}.type).also { packs ->
            require(packs.map { it.id }.distinct().size == packs.size)
            packs.forEach { pack ->
                require(pack.id.matches(Regex("[a-z0-9-]+")) && pack.version.matches(Regex("[a-f0-9]{12}")))
                require(pack.files.isNotEmpty() && pack.files.map { it.name }.distinct().size == pack.files.size)
                pack.files.forEach { file ->
                    require(file.name.matches(Regex("[a-zA-Z0-9_.-]+")) && !file.name.startsWith('.'))
                    require(file.bytes > 0 && file.sha256.matches(Regex("[a-f0-9]{64}")))
                }
            }
            cached = packs
        }
    }
    fun get(context: Context, id: String) = all(context).firstOrNull { it.id == id } ?: error("没有这个模型")
    fun root(context: Context) = File(context.filesDir, "model-store").apply { mkdirs() }
    fun directory(context: Context, pack: ModelPack) = File(root(context), "${pack.id}/${pack.version}").apply { mkdirs() }
    fun installed(context: Context, pack: ModelPack): Boolean {
        val dir = directory(context, pack)
        return File(dir, "installed.json").isFile && pack.files.all { File(dir, it.name).length() == it.bytes }
    }
    fun file(context: Context, id: String, name: String): File {
        val pack = get(context, id)
        require(installed(context, pack)) { "请先在模型管理安装${pack.title}，可下载或导入适用的离线包" }
        require(pack.files.any { it.name == name })
        return File(directory(context, pack), name)
    }
}
