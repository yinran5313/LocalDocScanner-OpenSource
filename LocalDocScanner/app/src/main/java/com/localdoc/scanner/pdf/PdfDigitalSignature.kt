/* Apache PDFBox CreateSignature/CreateSignatureBase (Apache-2.0) adapted to
 * Android: PKCS12 SAF stream, explicit BC provider, incremental detached CMS,
 * bounded memory and atomic new output. No private key or password is persisted.
 */
package com.localdoc.scanner.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar

data class SigningCertificate(val alias: String, val subject: String, val issuer: String,
    val sha256: String, val notAfter: String)

object PdfDigitalSignature {
    private val provider get() = BouncyCastleProvider()
    fun inspect(storeStream: InputStream, password: CharArray): List<SigningCertificate> {
        val store = load(storeStream, password)
        return store.aliases().toList().filter(store::isKeyEntry).map { alias ->
            val cert = store.getCertificate(alias) as? X509Certificate ?: error("仅支持X.509证书")
            SigningCertificate(alias, cert.subjectX500Principal.name, cert.issuerX500Principal.name,
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }, cert.notAfter.toString())
        }
    }
    fun sign(input: File, output: File, storeStream: InputStream, password: CharArray,
        alias: String = "", reason: String = "", signerName: String = ""): SigningCertificate {
        require(input.canonicalFile != output.canonicalFile && !output.exists()) { "签名必须另存新文件" }
        require(reason.length <= 300 && signerName.length <= 100) { "签名说明过长" }
        val bc = provider
        val store = load(storeStream, password)
        val available = store.aliases().toList().filter(store::isKeyEntry)
        val chosen = alias.ifBlank { available.singleOrNull() ?: error("请选择要使用的私钥证书") }
        require(chosen in available) { "没有找到所选私钥" }
        val key = store.getKey(chosen, password) as? PrivateKey ?: error("证书不包含私钥")
        val chain = store.getCertificateChain(chosen)?.map { it as? X509Certificate ?: error("证书链格式不支持") }
            ?: error("未找到证书链")
        val certificate = chain.first()
        certificate.checkValidity()
        require(certificate.keyUsage?.let { it.getOrElse(0) { false } || it.getOrElse(1) { false } } != false) { "证书不允许数字签名" }
        val algorithm = when (key.algorithm.uppercase()) { "RSA" -> "SHA256withRSA"; "EC", "ECDSA" -> "SHA256withECDSA"; else -> error("仅支持RSA或EC私钥") }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).use { doc ->
                require(doc.currentAccessPermission.canModify()) { "此PDF限制签名修改" }
                // Respect DocMDP: a certification signature may forbid further signatures/changes.
                val perms = doc.documentCatalog.cosObject.getCOSDictionary(com.tom_roush.pdfbox.cos.COSName.PERMS)
                require(perms?.containsKey(com.tom_roush.pdfbox.cos.COSName.DOCMDP) != true) { "PDF已有认证签名，请先确认其允许的修改范围" }
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    name = signerName.ifBlank { certificate.subjectX500Principal.name }
                    this.reason = reason
                    signDate = Calendar.getInstance()
                }
                SignatureOptions().use { options ->
                    options.preferredSignatureSize = 65536
                    doc.addSignature(signature, { content ->
                        val generator = CMSSignedDataGenerator()
                        val signer = JcaContentSignerBuilder(algorithm).setProvider(bc).build(key)
                        generator.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(
                            JcaDigestCalculatorProviderBuilder().setProvider(bc).build()).build(signer, certificate))
                        generator.addCertificates(JcaCertStore(chain))
                        content.use { stream ->
                            generator.generate(object : CMSTypedData {
                                override fun getContentType(): ASN1ObjectIdentifier = CMSObjectIdentifiers.data
                                override fun getContent(): Any = stream
                                override fun write(out: OutputStream) { stream.copyTo(out) }
                            }, false).encoded
                        }
                    }, options)
                    // PDFBox closes the supplied stream; reopen the completed file to sync it.
                    java.io.FileOutputStream(temporary).use { doc.saveIncremental(it) }
                    java.io.RandomAccessFile(temporary, "rw").use { it.channel.force(true) }
                }
            }
            check(temporary.renameTo(output)) { "无法保存签名文件" }
            return SigningCertificate(chosen, certificate.subjectX500Principal.name, certificate.issuerX500Principal.name,
                MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }, certificate.notAfter.toString())
        } finally { temporary.delete() }
    }
    private fun load(stream: InputStream, password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12", provider).apply { load(stream, password) }
}
