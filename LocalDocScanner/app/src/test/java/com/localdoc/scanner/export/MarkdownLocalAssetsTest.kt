package com.localdoc.scanner.export

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MarkdownLocalAssetsTest {
    @Test fun onlyAdjacentImagesAreReferencedWithoutNetworkOrTraversal() {
        val text="![页](source-1.jpg) ![重复](source-1.jpg) ![中文](原页.png) ![外部](https://example.com/photo.jpg) ![越界](../photo.jpg) ![绝对](/photo.jpg) ![盘符](C:\\photo.png)"
        assertEquals(listOf("source-1.jpg","原页.png"),MarkdownLocalAssets.names(text))
        assertFalse(MarkdownLocalAssets.safeName("签".repeat(100)+".png"))
    }
    @Test fun copiesOnlyReferencedResourcesAndLeavesUnrelatedFilesAlone() {
        val source=Files.createTempDirectory("md-assets").toFile(); val dest=Files.createTempDirectory("md-target").toFile()
        try {
            val md=java.io.File(source,"notes.md").apply { writeText("# 内容\n\n![页](page.jpg)") }
            java.io.File(source,"page.jpg").writeBytes(byteArrayOf(1,2,3));java.io.File(source,"unrelated.png").writeBytes(byteArrayOf(8))
            val target=java.io.File(dest,"renamed.md").apply { writeBytes(md.readBytes()) }
            assertEquals(listOf("page.jpg"),MarkdownLocalAssets.copyAdjacent(md,target).map { it.name })
            assertArrayEquals(byteArrayOf(1,2,3),java.io.File(dest,"page.jpg").readBytes())
            assertFalse(java.io.File(dest,"unrelated.png").exists())
        } finally { source.deleteRecursively();dest.deleteRecursively() }
    }
}
