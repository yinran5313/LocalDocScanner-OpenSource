package com.localdoc.scanner.export

import android.content.Context
import android.net.Uri
import com.localdoc.scanner.util.AtomicFiles
import java.io.File

/** Only adjacent local images; no network requests and no traversal of unrelated folders. */
object MarkdownLocalAssets {
    private val image=Regex("!\\[[^]\\r\\n]*]\\(([^)\\r\\n]+)\\)")
    fun names(text: String): List<String> = image.findAll(text).map { it.groupValues[1].trim() }
        .filter(::safeName).distinct().toList()
    fun safeName(name: String): Boolean = name.toByteArray(Charsets.UTF_8).size in 1..180 && name.none { it.isISOControl() || it in "/\\:<>|?*\"" } &&
        name !in setOf(".","..") && name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp","bmp")
    fun files(text: String, directory: File?): List<File> {
        if(directory==null)return emptyList()
        val root=directory.canonicalFile
        return names(text).map { File(root,it).canonicalFile }.filter { it.parentFile==root && it.isFile && it.length() in 1..20L*1024*1024 }
    }
    fun documentFiles(source: File): List<File> {
        if(source.extension.lowercase() !in setOf("md","markdown") || source.length()>2L*1024*1024)return emptyList()
        return withLocations(files(source.readText(Charsets.UTF_8),source.parentFile))
    }
    fun safeResource(name: String) = safeName(name) || name.matches(Regex("来源[0-9]+-[0-9]+\\.json"))
    private fun withLocations(images: List<File>): List<File> = images.flatMap { image ->
        val match=Regex("(?:source|figure)-([0-9]+)-([0-9]+)(?:-[0-9]+)?\\.jpg").matchEntire(image.name)
        val location=match?.let { File(image.parentFile,"来源${it.groupValues[1]}-${it.groupValues[2]}.json") }
        listOf(image)+listOfNotNull(location?.takeIf { it.isFile && it.length() in 1..10L*1024*1024 && it.canonicalFile.parentFile==image.canonicalFile.parentFile })
    }
    fun ownedSource(context: Context, uri: Uri): File? = runCatching {
        val source=when {
            uri.scheme=="file" -> File(requireNotNull(uri.path))
            uri.scheme=="content" && uri.authority=="${context.packageName}.fileprovider" -> {
                val parts=uri.pathSegments; val root=when(parts.firstOrNull()) { "files" -> context.filesDir; "cache" -> context.cacheDir; else -> return null }
                File(root,parts.drop(1).joinToString("/"))
            }
            else -> return null
        }.canonicalFile
        source.takeIf { it.isFile && listOf(context.filesDir,context.cacheDir).any { dir -> it.path.startsWith(dir.canonicalPath+File.separator) } }
    }.getOrNull()
    fun copyAdjacent(source: File?, target: File): List<File> {
        if(source==null || target.extension.lowercase() !in setOf("md","markdown"))return emptyList()
        require(target.length()<=2L*1024*1024) { "Markdown过大，请使用ZIP保留附件" }
        return copyFiles(withLocations(files(target.readText(Charsets.UTF_8),source.parentFile)),requireNotNull(target.parentFile))
    }
    fun copyDocumentFiles(source: File, directory: File): List<File> = copyFiles(documentFiles(source),directory)
    private fun copyFiles(candidates: List<File>, directory: File): List<File> {
        require(candidates.size<=500 && candidates.sumOf { it.length() }<=256L*1024*1024) { "Markdown附件过多，请分组或保存ZIP" }
        return candidates.map { file ->
            val destination=File(directory,file.name)
            if(file.canonicalFile!=destination.canonicalFile) {
                require(directory.usableSpace>file.length()+8L*1024*1024) { "保存附件的空间不足" }
                AtomicFiles.copy(file,destination)
            }
            destination
        }
    }
}
