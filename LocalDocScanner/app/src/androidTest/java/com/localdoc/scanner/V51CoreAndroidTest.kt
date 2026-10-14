package com.localdoc.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.localdoc.scanner.data.DocRepository
import com.localdoc.scanner.data.LibraryFolders
import com.localdoc.scanner.tokenizer.SentencePieces
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class V51CoreAndroidTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    @Test fun nativeSentencePieceMatchesIndependentReferenceForUnicodeAndNormalization() {
        val file = File(app.cacheDir, "native-spm-proof.spm")
        try {
            assets.open("spm-fixture.spm").use { input -> file.outputStream().use(input::copyTo) }
            val fixtures = JSONArray(assets.open("spm-fixtures.json").bufferedReader().readText())
            SentencePieces(file.absolutePath).use { tokenizer -> for (i in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(i); val pieces = fixture.getJSONArray("pieces")
                assertEquals((0 until pieces.length()).map { pieces.getString(it) }, tokenizer.encode(fixture.getString("text")))
            } }
        } finally { file.delete() }
    }
    @Test fun importedAndNewOfficeFilesSurviveRenameBackupAndRestore(): Unit = runBlocking {
        val repo = DocRepository(app); val id = repo.createOffice("docx", "V51测试文档-${System.nanoTime()}")
        val backup = File(app.cacheDir, "v51-backup-proof.zip"); val folder = "测试空文件夹-${System.nanoTime()}"
        try {
            val document = repo.ownedFile(id)!!
            ZipFile(document).use { zip -> assertTrue(zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText().contains("w:w=\"11906\"")) }
            repo.rename(id, "V51重命名且省略扩展名")
            LibraryFolders.add(app, folder)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", backup)
            val saved = repo.backupLibrary(uri); assertTrue(saved.error, saved.success)
            val restored = repo.restoreLibrary(uri); assertTrue(restored.error, restored.success)
            val item = repo.observeDocs().first().first { it.id != id && it.title.startsWith("V51重命名且省略扩展名") }
            assertEquals("FILE", item.kind); assertEquals("docx", File(item.filePath).extension)
            assertTrue(File(item.filePath).length() > 0)
            repo.deleteForever(item.id)
        } finally { repo.deleteForever(id); backup.delete() }
        Unit
    }
}
