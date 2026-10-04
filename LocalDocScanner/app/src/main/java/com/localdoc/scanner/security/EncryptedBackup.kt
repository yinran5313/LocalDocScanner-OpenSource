package com.localdoc.scanner.security

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.*
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Versioned backup envelope. AES-GCM is provided by BC, not a local cipher implementation. */
object EncryptedBackup {
    private val magic = "LDSBAK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600000
    private const val HEADER_SIZE = 44
    fun output(raw: OutputStream, password: CharArray): OutputStream {
        require(password.size in 8..1024) { "备份密码至少8个字符，建议使用较长的独立密码" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            write(magic); writeInt(1); writeInt(ITERATIONS); write(salt); write(nonce)
        } }.toByteArray()
        val key = derive(password, salt, ITERATIONS)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        try { cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, header)) } finally { key.fill(0) }
        raw.write(header)
        return CipherOutputStream(raw, cipher)
    }
    /** No caller may read the plaintext until authentication succeeds and this returns. */
    fun decrypt(input: InputStream, output: File, password: CharArray, maxBytes: Long = 8L * 1024 * 1024 * 1024) {
        require(!output.exists()) { "恢复临时文件已经存在" }
        require(password.size <= 1024 && maxBytes > 0)
        val staged = File(output.parentFile, "${output.name}.${UUID.randomUUID()}.part")
        output.parentFile?.mkdirs()
        try {
            val header = ByteArray(HEADER_SIZE)
            DataInputStream(input).readFully(header)
            val decoder = DataInputStream(ByteArrayInputStream(header))
            val found = ByteArray(8).also(decoder::readFully)
            require(found.contentEquals(magic) && decoder.readInt() == 1) { "不是本应用支持的加密备份" }
            val iterations = decoder.readInt()
            require(iterations in 100000..2000000) { "备份的密码参数无效" }
            val salt = ByteArray(16).also(decoder::readFully)
            val nonce = ByteArray(12).also(decoder::readFully)
            val key = derive(password, salt, iterations)
            val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
            try { cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, header)) } finally { key.fill(0) }
            FileOutputStream(staged).use { target ->
                val sourceBuffer = ByteArray(64 * 1024)
                val clearBuffer = ByteArray(sourceBuffer.size + 32)
                var countBytes = 0L
                try {
                    while (true) {
                        val count = input.read(sourceBuffer); if (count < 0) break
                        val written = cipher.processBytes(sourceBuffer, 0, count, clearBuffer, 0)
                        countBytes += written; require(countBytes <= maxBytes) { "备份超过恢复大小限制" }
                        target.write(clearBuffer, 0, written)
                    }
                    val finalCount = cipher.doFinal(clearBuffer, 0)
                    countBytes += finalCount; require(countBytes <= maxBytes) { "备份超过恢复大小限制" }
                    target.write(clearBuffer, 0, finalCount); target.fd.sync()
                } finally { sourceBuffer.fill(0); clearBuffer.fill(0) }
            }
            check(staged.renameTo(output)) { "无法提交验证完成的备份" }
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw IOException("密码不正确或备份已损坏；没有恢复任何文档", e)
        } finally { staged.delete() }
    }
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
