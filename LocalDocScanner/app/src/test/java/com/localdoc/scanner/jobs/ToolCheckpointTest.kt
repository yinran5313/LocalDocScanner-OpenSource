package com.localdoc.scanner.jobs

import com.google.gson.Gson
import com.localdoc.scanner.util.AtomicFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolCheckpointTest {
    private fun fixture(test: (File, ToolCheckpoints) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("tool-resume-").toFile()
        try { test(dir, ToolCheckpoints(dir, Gson())) } finally { dir.deleteRecursively() }
    }
    @Test fun restartReusesReceiptAndValidatesOutputContent() = fixture { dir, store ->
        val output = File(dir, "page.txt").apply { writeText("完成页面") }
        store.save("page:0", "{\"text\":\"完成页面\"}", listOf(output))
        val reopened = ToolCheckpoints(dir, Gson())
        assertEquals("{\"text\":\"完成页面\"}", reopened.load("page:0")?.payload)
        output.writeText("损坏")
        assertNull(reopened.load("page:0"))
    }
    @Test fun missingOutputInvalidatesOnlyItsOwnUnit() = fixture { dir, store ->
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.txt").apply { writeText("b") }
        store.save("0", "first", listOf(a)); store.save("1", "second", listOf(b))
        b.delete()
        assertNotNull(store.load("0")); assertNull(store.load("1"))
    }
    @Test fun malformedReceiptIsTreatedAsUnfinishedWithoutDeletingOutput() = fixture { dir, store ->
        val output = File(dir, "a.txt").apply { writeText("keep") }
        store.save("0", "text", listOf(output))
        File(dir, "units").listFiles()!!.single().writeText("{")
        assertNull(store.load("0")); assertEquals("keep", output.readText())
    }
    @Test fun finalPackagingFailureDoesNotDestroyCompletedPageReceipts() = fixture { dir, store ->
        val page = File(dir, "page.txt").apply { writeText("page") }
        val pdf = File(dir, "final.pdf").apply { writeText("previous-valid-output") }
        store.save("page", "completed", listOf(page))
        try { AtomicFiles.write(pdf) { it.writeText("partial"); error("磁盘写入失败") }; fail() } catch (_: IllegalStateException) { }
        assertEquals("previous-valid-output", pdf.readText()); assertNotNull(store.load("page"))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun emptyOutputsCannotBeCommittedAsSuccessfulFiles() = fixture { dir, store ->
        val empty = File(dir, "empty.txt").apply { createNewFile() }
        try { store.save("empty", "", listOf(empty)); fail() } catch (_: IllegalArgumentException) { }
        assertNull(store.load("empty"))
        store.save("text-only", "recognized text", emptyList())
        assertEquals("recognized text", store.load("text-only")?.payload)
    }
}
