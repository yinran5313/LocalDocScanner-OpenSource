package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.*
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.math.BigInteger
import java.nio.file.Files
import java.security.*
import java.util.Date

class PdfDigitalSignatureTest {
    private fun keyStoreBytes(password: CharArray): ByteArray {
        val bc = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Local test certificate")
        val certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(
            JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date(now - 60000), Date(now + 86400000), subject, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private)))
        val store = KeyStore.getInstance("PKCS12", bc).apply { load(null, password); setKeyEntry("test", pair.private, password, arrayOf(certificate)) }
        return ByteArrayOutputStream().apply { store.store(this, password) }.toByteArray()
    }
    @Test fun detachedCmsVerifiesAndOriginalPdfStaysUnchanged() {
        val folder = Files.createTempDirectory("pdf-digital-sign").toFile()
        val password = "secret".toCharArray()
        try {
            val input = File(folder, "input.pdf"); val output = File(folder, "signed.pdf")
            PDDocument().use { it.addPage(PDPage()); it.save(input) }
            val original = input.readBytes(); val bytes = keyStoreBytes(password)
            val identities = PdfDigitalSignature.inspect(ByteArrayInputStream(bytes), password)
            assertEquals("test", identities.single().alias)
            val identity = PdfDigitalSignature.sign(input, output, ByteArrayInputStream(bytes), password, "test", "Reviewed")
            assertEquals(identities.single().sha256, identity.sha256)
            PDDocument.load(output).use { doc ->
                val signature = doc.signatureDictionaries.single()
                val content = output.inputStream().use(signature::getSignedContent)
                // PDF /Contents contains a zero-filled reservation after the CMS object.
                // BC 1.86 byte-array parsing correctly rejects those trailing bytes.
                val padded = output.inputStream().use(signature::getContents)
                val stream = ByteArrayInputStream(padded)
                val cms = org.bouncycastle.asn1.ASN1InputStream(stream).use { it.readObject().encoded.also {
                    assertTrue(stream.readBytes().all { byte -> byte == 0.toByte() })
                } }
                val data = CMSSignedData(CMSProcessableByteArray(content), cms)
                val signer = data.signerInfos.signers.single()
                val certificate = data.certificates.getMatches(null).single { signer.sid.match(it) }
                assertTrue(signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider()).build(certificate)))
                val changed = content.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
                val altered = CMSSignedData(CMSProcessableByteArray(changed), cms)
                assertThrows(Exception::class.java) { altered.signerInfos.signers.single().verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider()).build(certificate)) }
            }
            assertArrayEquals(original, input.readBytes())
            assertTrue(output.readBytes().take(original.size).toByteArray().contentEquals(original))
        } finally { password.fill('\u0000'); folder.deleteRecursively() }
    }
    @Test fun wrongCertificatePasswordProducesNoOutput() {
        val folder = Files.createTempDirectory("pdf-sign-failure").toFile()
        try {
            val input = File(folder, "input.pdf"); val output = File(folder, "signed.pdf")
            PDDocument().use { it.addPage(PDPage()); it.save(input) }
            val bytes = keyStoreBytes("secret".toCharArray())
            assertThrows(Exception::class.java) { PdfDigitalSignature.sign(input, output, ByteArrayInputStream(bytes), "wrong".toCharArray()) }
            assertFalse(output.exists())
        } finally { folder.deleteRecursively() }
    }
}
