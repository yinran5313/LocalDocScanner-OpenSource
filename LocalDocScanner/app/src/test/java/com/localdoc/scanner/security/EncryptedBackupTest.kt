package com.localdoc.scanner.security

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files

class EncryptedBackupTest {
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        EncryptedBackup.output(this, "独立长密码-2026".toCharArray()).use { it.write(bytes) }
    }.toByteArray()
    @Test fun streamingRoundTripKeepsBytesAndRandomizesEnvelope() {
        val root = Files.createTempDirectory("secure-backup").toFile()
        try {
            val source = ByteArray(200000) { (it % 251).toByte() }
            val encoded = encrypt(source)
            assertFalse(encoded.contentEquals(encrypt(source)))
            val result = File(root, "verified.zip")
            EncryptedBackup.decrypt(ByteArrayInputStream(encoded), result, "独立长密码-2026".toCharArray())
            assertArrayEquals(source, result.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun wrongPasswordTamperingAndTruncationNeverCommitPlaintext() {
        val root = Files.createTempDirectory("secure-backup-failure").toFile()
        try {
            val encoded = encrypt(ByteArray(80000) { 17 })
            val variants = listOf(encoded to "wrong password", encoded.copyOf().also { it[46] = (it[46].toInt() xor 1).toByte() } to "独立长密码-2026",
                encoded.copyOf().also { it[17] = (it[17].toInt() xor 1).toByte() } to "独立长密码-2026", encoded.copyOf(encoded.size - 1) to "独立长密码-2026")
            variants.forEachIndexed { index, (bytes, password) ->
                val result = File(root, "$index.zip")
                assertThrows(Exception::class.java) { EncryptedBackup.decrypt(ByteArrayInputStream(bytes), result, password.toCharArray()) }
                assertFalse(result.exists()); assertTrue(root.listFiles()!!.none { it.extension == "part" })
            }
            assertThrows(Exception::class.java) { EncryptedBackup.decrypt(ByteArrayInputStream(encoded), File(root, "limit"), "独立长密码-2026".toCharArray(), 100) }
            assertFalse(File(root, "limit").exists())
        } finally { root.deleteRecursively() }
    }
}
