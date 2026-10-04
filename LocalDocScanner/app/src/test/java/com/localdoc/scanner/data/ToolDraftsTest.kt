package com.localdoc.scanner.data

import com.localdoc.scanner.model.FileKind
import com.localdoc.scanner.model.ToolEntry
import com.localdoc.scanner.ui.ToolRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolDraftsTest {
    @Test fun restoresRequestWithPathsAndOriginalNames() {
        val source = ToolRequest(ToolEntry("batch_extract", "票据批量汇总", FileKind.ANY, true), listOf(File(System.getProperty("java.io.tmpdir"), "import/1.pdf").absoluteFile), listOf("发票原稿.pdf"))
        val restored = ToolDrafts.gson.fromJson(ToolDrafts.gson.toJson(source), ToolRequest::class.java)
        assertEquals(source, restored)
        assertEquals(ToolDrafts.key(source), ToolDrafts.key(restored))
    }
}
