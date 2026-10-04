package com.localdoc.scanner.jobs

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.util.UUID

class OcrCheckpointTest {
    @Test fun onlyMatchingCompletedReceiptsCanBeReused() {
        val saved = OcrPageCheckpoint("p1", "abc", "recipe", true, 42)
        assertTrue(OcrCheckpointStore.canReuse(saved, "abc", "recipe", 42))
        assertFalse(OcrCheckpointStore.canReuse(saved, "changed", "recipe", 42))
        assertFalse(OcrCheckpointStore.canReuse(saved, "abc", "new recipe", 42))
        assertFalse(OcrCheckpointStore.canReuse(saved, "abc", "recipe", 43))
        assertFalse(OcrCheckpointStore.canReuse(saved.copy(done = false), "abc", "recipe", 42))
    }
    @Test fun atomicJournalKeepsReceiptsAcrossReloadAndHashesEntireInput() {
        val root = Files.createTempDirectory("ocr-journal").toFile()
        try {
            val image = java.io.File(root, "input").apply { writeBytes(ByteArray(140000) { 8 }) }
            val hash = OcrCheckpointStore.hash(image)
            val job = OcrCheckpoint(UUID.randomUUID().toString(), "doc", true, listOf(OcrPageCheckpoint("p1", hash, "r", true, 42)))
            OcrCheckpointStore.save(root, job)
            assertEquals(job, OcrCheckpointStore.load(root, job.id))
            image.appendBytes(byteArrayOf(9)); assertNotEquals(hash, OcrCheckpointStore.hash(image))
            assertThrows(IllegalArgumentException::class.java) { OcrCheckpointStore.file(root, "../escape") }
            assertTrue(root.listFiles()!!.none { it.extension == "part" })
        } finally { root.deleteRecursively() }
    }
}
