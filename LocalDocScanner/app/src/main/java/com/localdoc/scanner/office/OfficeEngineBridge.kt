package com.localdoc.scanner.office

import android.annotation.SuppressLint
import android.content.ClipData
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

data class OfficeEngineInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val officialFdroidSignature: Boolean,
    val snapshot: Boolean,
    val embedded: Boolean = false
)

/**
 * 将 Office 工作副本交给同一应用包中的 Collabora 完整引擎。
 * 主App仅授予当前content URI读写权限，不授予整个文档库目录。
 */
object OfficeEngineBridge {
    const val STABLE_PACKAGE = "com.collabora.libreoffice"
    const val SNAPSHOT_PACKAGE = "com.collabora.libreoffice.snapshot"
    const val OFFICIAL_FDROID_CERT_SHA256 = "573258c84e149b5f4d9299e7434b2b69a8410372921d4ae586ba91ec767892cc"
    const val OFFICIAL_INSTALL_PAGE = "https://www.collaboraonline.com/collabora-office-android-ios/"
    private val packages = listOf(STABLE_PACKAGE, SNAPSHOT_PACKAGE)
    const val EMBEDDED_ACTIVITY = "org.libreoffice.androidlib.LOActivity"

    @SuppressLint("InlinedApi")
    fun installed(context: Context): OfficeEngineInfo? {
        val pm = context.packageManager
        val embedded = runCatching {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(ComponentName(context.packageName, EMBEDDED_ACTIVITY), 0)
        }.getOrNull()
        if (embedded != null && embedded.enabled) {
            return OfficeEngineInfo(context.packageName, "Collabora Office（内置）", "26.04.3.1",
                officialFdroidSignature = false, snapshot = false, embedded = true)
        }
        return packages.firstNotNullOfOrNull { packageName ->
            runCatching {
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                } else if (Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                }
                val signatures = if (Build.VERSION.SDK_INT >= 28) {
                    val signing = info.signingInfo
                    if (signing?.hasMultipleSigners() == true) signing.apkContentsSigners else signing?.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures
                }.orEmpty()
                val official = signatures.any { signature -> sha256(signature.toByteArray()) == OFFICIAL_FDROID_CERT_SHA256 }
                OfficeEngineInfo(
                    packageName = packageName,
                    label = info.applicationInfo?.loadLabel(pm)?.toString().orEmpty().ifBlank { "Collabora Office" },
                    versionName = info.versionName.orEmpty(),
                    officialFdroidSignature = official,
                    snapshot = packageName == SNAPSHOT_PACKAGE
                )
            }.getOrNull()
        }
    }

    fun editIntent(context: Context, file: File, mime: String, engine: OfficeEngineInfo): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return editIntent(context, uri, mime, engine)
    }

    fun editIntent(context: Context, uri: Uri, mime: String, engine: OfficeEngineInfo): Intent =
        Intent(Intent.ACTION_EDIT).apply {
            if (engine.embedded) component = ComponentName(context.packageName, EMBEDDED_ACTIVITY)
            else setPackage(engine.packageName)
            setDataAndType(uri, mime)
            clipData = ClipData.newUri(context.contentResolver, "Office工作副本", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    fun openInstallPage(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_INSTALL_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun canResolve(context: Context, intent: Intent): Boolean = intent.resolveActivity(context.packageManager) != null

    fun openEditor(context: Context, file: File, mime: String, engine: OfficeEngineInfo): Boolean =
        openEditor(context, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), mime, engine)

    fun openEditor(context: Context, uri: Uri, mime: String, engine: OfficeEngineInfo): Boolean = runCatching {
        val intent = editIntent(context, uri, mime, engine)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(canResolve(context, intent)) { "完整引擎不能处理此格式" }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
