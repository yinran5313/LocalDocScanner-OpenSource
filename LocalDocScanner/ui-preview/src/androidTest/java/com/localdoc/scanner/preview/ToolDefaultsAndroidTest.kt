package com.localdoc.scanner.preview
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.localdoc.scanner.data.ToolDrafts
import com.localdoc.scanner.model.*
import com.localdoc.scanner.ui.ToolRequest
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class ToolDefaultsAndroidTest {
    @Test fun untouchedDefaultsReachTaskAndManualChoiceSurvivesReopen() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val request=ToolRequest(ToolEntry("images_to_pdf","测试",FileKind.IMAGE),listOf(File(context.cacheDir,"defaults-${java.util.UUID.randomUUID()}.jpg")),listOf("测试"))
        val key=ToolDrafts.key(request)+":paperV5"
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val state=ToolDrafts.state(context,key,String::class.java) { "LETTER" }
                ToolDrafts.acquire(key)
                assertEquals("\"LETTER\"",ToolDrafts.snapshot(context,request)["paperV5"])
                state.value="FIT_IMAGE"
                ToolDrafts.release(key)
                val reopened=ToolDrafts.state(context,key,String::class.java) { "A4" }
                ToolDrafts.acquire(key)
                assertEquals("FIT_IMAGE",reopened.value)
                assertEquals("\"FIT_IMAGE\"",ToolDrafts.snapshot(context,request)["paperV5"])
                ToolDrafts.release(key)
            }
        } finally { ToolDrafts.forget(context,request) }
    }
}
