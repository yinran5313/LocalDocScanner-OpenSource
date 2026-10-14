package com.localdoc.scanner

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.models.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ModelDownloadAndroidTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun resumesRangesFallsBackAndRejectsHashMismatch(): Unit = runBlocking {
        val original=ModelCatalog.get(app,"table").files.first { it.name=="inference.yml" }
        val body=ModelCatalog.file(app,"table",original.name).readBytes()
        val broken=original.urls[0].replace("inference.yml","missing-test-file.yml")
        val file=original.copy(urls=listOf(broken)+original.urls)
        val pack=ModelPack("download-proof","测试下载","test","test","000000000001","Apache-2.0","test",listOf(file))
        val bad=pack.copy(id="download-bad",files=listOf(file.copy(sha256="0".repeat(64))))
        try {
            val dir=ModelCatalog.directory(app,pack); File(dir,file.name+".part").writeBytes(body.take(64).toByteArray())
            var last=0L; ModelDownloads.download(app,pack,0) { done,total -> assertTrue(done in 0..total); last=done }
            assertEquals(file.bytes,last); assertTrue(ModelDownloads.verify(dir,file)); assertTrue(ModelCatalog.installed(app,pack))
            assertTrue(runCatching { ModelDownloads.download(app,bad,1) { _,_ -> } }.isFailure)
            assertFalse(ModelCatalog.installed(app,bad))
        } finally { for (id in listOf(pack.id,bad.id)) {
            val root=ModelCatalog.root(app).canonicalFile; val owned=File(root,id).canonicalFile; check(owned.parentFile==root); owned.deleteRecursively()
        } }
        Unit
    }
    @Test fun failedOfflineImportLeavesExistingVerifiedModelUsable(): Unit = runBlocking {
        val pack=ModelCatalog.get(app,"table"); val dir=ModelCatalog.directory(app,pack)
        assertTrue(ModelCatalog.installed(app,pack)); val before=pack.files.associate { it.name to ModelDownloads.hash(File(dir,it.name)) }
        val zip=File(app.cacheDir,"bad-model-proof.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("model.json")); out.write(JSONObject().put("id",pack.id).put("version",pack.version).toString().toByteArray()); out.closeEntry()
                for (file in pack.files) { out.putNextEntry(ZipEntry(file.name)); if (file.name.endsWith(".yml")) out.write(ByteArray(file.bytes.toInt())) else File(dir,file.name).inputStream().use { it.copyTo(out) }; out.closeEntry() }
            }
            assertTrue(runCatching { ModelDownloads.import(app,Uri.fromFile(zip)) }.isFailure)
            assertTrue(ModelCatalog.installed(app,pack)); assertEquals(before,pack.files.associate { it.name to ModelDownloads.hash(File(dir,it.name)) })
        } finally { zip.delete() }
        Unit
    }
}
