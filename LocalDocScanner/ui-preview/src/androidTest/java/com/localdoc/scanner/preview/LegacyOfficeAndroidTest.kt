package com.localdoc.scanner.preview
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class LegacyOfficeAndroidTest {
    @Test fun binaryTextExtractionWorksOnAndroidWithoutAwt() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf("legacy-simple.doc" to "This is a simple file", "legacy-sheet.xls" to "拾页表格索引", "legacy-slides.ppt" to "拾页PPT索引").forEach { (name,expected) ->
            val file=File(context.cacheDir,name)
            try { context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }; assertTrue(name,com.localdoc.scanner.office.LegacyOfficeText.read(file).contains(expected)) }
            finally { file.delete() }
        }
    }
}
