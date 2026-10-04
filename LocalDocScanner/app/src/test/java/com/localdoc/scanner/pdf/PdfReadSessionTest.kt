package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PdfReadSessionTest {
    @Test fun explicitWorkingCopyIsReadableAndLeavesOriginalEncrypted() {
        val folder = Files.createTempDirectory("pdf-working-copy").toFile()
        try {
            val source = java.io.File(folder, "original.pdf")
            val out = java.io.File(folder, "work.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
                doc.save(source)
            }
            val original = source.readBytes()
            PdfReadSession.open(source, "user").use { it.createWorkingCopy(out) }
            PDDocument.load(out).use { assertFalse(it.isEncrypted); assertEquals(1, it.numberOfPages) }
            assertArrayEquals(original, source.readBytes())
            assertThrows(InvalidPasswordException::class.java) { PdfReadSession.open(source).close() }
            PdfReadSession.open(source, "user").use {
                assertThrows(IllegalArgumentException::class.java) { it.createWorkingCopy(out) }
                assertThrows(IllegalArgumentException::class.java) { it.createWorkingCopy(source) }
            }
            assertFalse(folder.listFiles()!!.any { it.extension == "part" })
        } finally { folder.deleteRecursively() }
    }

    @Test fun readOnlyUserPasswordCannotCreateWorkingCopy() {
        val folder = Files.createTempDirectory("pdf-permissions").toFile()
        try {
            val source = java.io.File(folder, "readonly.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.protect(StandardProtectionPolicy("owner", "read", AccessPermission().apply { setCanModify(false) }))
                doc.save(source)
            }
            val out = java.io.File(folder, "work.pdf")
            PdfReadSession.open(source, "read").use {
                assertFalse(it.canCreateWorkingCopy)
                assertThrows(IllegalArgumentException::class.java) { it.createWorkingCopy(out) }
            }
            assertFalse(out.exists())
            PdfReadSession.open(source, "owner").use { assertTrue(it.canCreateWorkingCopy); it.createWorkingCopy(out) }
            assertTrue(out.isFile)
        } finally { folder.deleteRecursively() }
    }
    @Test fun passwordProtectedDocumentCanBeReadWithoutWritingDecryptedCopy() {
        val folder = Files.createTempDirectory("protected-reader").toFile()
        try {
            val file = java.io.File(folder, "protected.pdf")
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(40f, 700f)
                    stream.showText("Saved current version"); stream.endText()
                }
                doc.protect(StandardProtectionPolicy("owner", "secret", AccessPermission()).apply { encryptionKeyLength = 128 })
                doc.save(file)
            }
            assertThrows(InvalidPasswordException::class.java) { PdfReadSession.open(file, "wrong").close() }
            PdfReadSession.open(file, "secret").use { session ->
                assertEquals(1, session.pageCount)
                assertTrue(session.pageText(0).contains("Saved current version"))
            }
            assertEquals(listOf("protected.pdf"), folder.listFiles()!!.map { it.name })
        } finally { folder.deleteRecursively() }
    }
}
