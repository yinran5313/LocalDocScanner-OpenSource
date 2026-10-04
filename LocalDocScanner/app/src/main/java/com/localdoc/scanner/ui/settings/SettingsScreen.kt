package com.localdoc.scanner.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.security.AppLock
import com.localdoc.scanner.office.OfficeEngineBridge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lockEnabled by remember { mutableStateOf(AppLock.enabled(context)) }
    var configureLock by remember { mutableStateOf(false) }
    var disableLock by remember { mutableStateOf(false) }
    var configureBiometric by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(AppLock.biometricEnabled(context)) }
    var officeEngine by remember { mutableStateOf(OfficeEngineBridge.installed(context)) }
    var encryptedBackupPassword by remember { mutableStateOf<CharArray?>(null) }
    var secureBackupDialog by remember { mutableStateOf(false) }
    var secureRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { encryptedBackupPassword?.fill('\u0000'); encryptedBackupPassword = null } }
    val secureBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = encryptedBackupPassword
        encryptedBackupPassword = null
        if (uri == null) password?.fill('\u0000')
        else if (password == null) status = "密码会话已结束，请重新开始加密备份"
        else scope.launch {
            busy = true
            try {
                val result = vm.backupEncryptedLibrary(uri, password)
                status = if (result.success) "加密备份完成：${result.documentCount} 份、${result.pageCount} 页；${com.localdoc.scanner.output.OutputHistoryStore.describeDestination(context, uri)}" else "加密备份失败：${result.error}"
                if (result.success) com.localdoc.scanner.output.OutputHistoryStore.recordDirectSaved(context,
                    com.localdoc.scanner.output.OutputHistoryStore.displayName(context, uri), "application/octet-stream", uri,
                    com.localdoc.scanner.output.OutputHistoryStore.describeDestination(context, uri))
            } finally { password.fill('\u0000'); busy = false }
        }
    }
    val secureRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) secureRestoreUri = uri }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = vm.backupLibrary(uri)
            status = if (result.success) "备份完成：${result.documentCount} 份文档、${result.pageCount} 页" else "备份失败：${result.error}"
            busy = false
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = vm.restoreLibrary(uri)
            status = if (result.success) "恢复完成：${result.documentCount} 份文档、${result.pageCount} 页" else "恢复失败：${result.error}"
            busy = false
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置与存储") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                "文档库",
                "扫描原图、编辑参数和处理后的页面保存在本应用内部。你可以随时重新打开、编辑和导出。"
            )
            InfoCard(
                "保存到手机",
                "在文档里选择“导出”，再由系统文件选择器决定保存位置。应用会显示真实文件名，不把内部目录冒充手机文件夹。"
            )
            InfoCard(
                "导出默认值",
                "PDF默认使用A4页面和高清质量。每次导出时都可以改为Letter、适合图片或标准质量；JPG会按当前页序逐张保存。"
            )
            InfoCard(
                "卸载提醒",
                "卸载应用或清除应用数据会删除文档库。重要文件请另存到手机或分享备份。"
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("完整Office引擎", style = MaterialTheme.typography.titleMedium)
                    val engine = officeEngine
                    if (engine == null) {
                        Text("内置Office组件没有就绪，请检查安装包是否完整。")
                        Button(
                            onClick = { OfficeEngineBridge.openInstallPage(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("打开官方安装页") }
                    } else {
                        Text("已连接：${engine.label} ${engine.versionName}")
                        Text(
                            if (engine.embedded) "已内置在本应用，可离线阅读和编辑Word、Excel、PPT。"
                            else if (engine.officialFdroidSignature) "签名与官方F-Droid稳定版一致。"
                            else "当前安装包签名与官方F-Droid版不同；可能来自Google Play或其他渠道。",
                            color = if (engine.embedded || engine.officialFdroidSignature) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(
                        onClick = { officeEngine = OfficeEngineBridge.installed(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新检测") }
                    TextButton(onClick = {
                        context.startActivity(android.content.Intent(context, com.localdoc.scanner.office.OfficeLicenseActivity::class.java))
                    }) { Text("Office许可与源码") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("完整文档库备份", style = MaterialTheme.typography.titleMedium)
                    Text("备份包含原图、处理图、裁边参数、页序、文件夹、标签和OCR文字。恢复时生成新文档，不覆盖现有内容。")
                    Button(
                        onClick = {
                            val name = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            backup.launch("本地扫描文档库_$name.ldbackup.zip")
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("备份到手机") }
                    Button(
                        onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从备份恢复") }
                    Button(onClick = { secureBackupDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("密码加密备份") }
                    Button(onClick = { secureRestore.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复加密备份") }
                    Text("加密备份保护导出的整个备份内容，内部文档库仍为普通存储。恢复验证时会短暂解密到应用缓存，完成后删除。备份密码独立于应用密码锁，丢失后无法恢复。", style = MaterialTheme.typography.bodySmall)
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用密码锁", style = MaterialTheme.typography.titleMedium)
                    Text(if (lockEnabled) "已开启。应用离开后台30秒后会重新锁定。" else "保护应用入口；这不等于加密手机存储中的原文件。")
                    Button(onClick = { configureLock = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (lockEnabled) "更改密码" else "设置密码")
                    }
                    if (lockEnabled) {
                        TextButton(onClick = { configureBiometric = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (biometricEnabled) "关闭指纹解锁" else "开启指纹解锁")
                        }
                        TextButton(onClick = { disableLock = true }, modifier = Modifier.fillMaxWidth()) { Text("关闭密码锁") }
                    }
                }
            }
            Text("版本 ${com.localdoc.scanner.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (secureBackupDialog || secureRestoreUri != null) {
        val restoring = secureRestoreUri != null
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { secureBackupDialog = false; secureRestoreUri = null },
            title = { Text(if (restoring) "恢复密码加密备份" else "设置独立备份密码") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(password, { password = it }, label = { Text(if (restoring) "备份密码" else "至少8个字符的备份密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (!restoring) OutlinedTextField(confirm, { confirm = it }, label = { Text("再次输入") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = {
                TextButton(onClick = {
                    if (password.length > 1024 || (!restoring && (password.length < 8 || password != confirm))) {
                        message = "密码至少8个字符，两次输入须一致，最多1024个字符"
                    } else {
                        val secret = password.toCharArray(); password = ""; confirm = ""
                        val uri = secureRestoreUri
                        secureBackupDialog = false; secureRestoreUri = null
                        if (restoring && uri != null) scope.launch {
                            busy = true
                            try {
                                val result = vm.restoreEncryptedLibrary(uri, secret)
                                status = if (result.success) "恢复完成：${result.documentCount} 份、${result.pageCount} 页" else "恢复失败：${result.error}"
                            } finally { secret.fill('\u0000'); busy = false }
                        } else {
                            encryptedBackupPassword?.fill('\u0000'); encryptedBackupPassword = secret
                            secureBackup.launch("本地扫描_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.ldbackup.enc")
                        }
                    }
                }) { Text(if (restoring) "验证并恢复" else "选择保存位置") }
            }, dismissButton = { TextButton(onClick = { secureBackupDialog = false; secureRestoreUri = null }) { Text("取消") } })
    }

    if (configureBiometric) {
        var pin by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf<androidx.biometric.BiometricPrompt?>(null) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { prompt?.cancelAuthentication() } }
        AlertDialog(onDismissRequest = { configureBiometric = false }, title = { Text("指纹解锁设置") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField("当前应用密码", pin) { pin = it }
                Text("仅保护应用入口；加密文件使用独立密码。其他已登记的强生物识别也可解锁。")
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(onClick = {
                when {
                    !AppLock.verify(context, pin) -> message = "应用密码不正确"
                    biometricEnabled -> { AppLock.setBiometric(context, pin, false); biometricEnabled = false; configureBiometric = false }
                    !com.localdoc.scanner.security.BiometricUnlock.available(context) -> message = "请先在手机系统中登记强生物识别"
                    else -> com.localdoc.scanner.security.BiometricUnlock.activity(context)?.let { activity ->
                        prompt = com.localdoc.scanner.security.BiometricUnlock.prompt(activity, {
                            biometricEnabled = AppLock.setBiometric(context, pin, true)
                            pin = ""; configureBiometric = false
                        }) { message = it }
                    }
                }
            }) { Text(if (biometricEnabled) "关闭" else "验证并开启") }
        }, dismissButton = { TextButton(onClick = { configureBiometric = false }) { Text("取消") } })
    }

    if (configureLock) {
        var current by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { configureLock = false },
            title = { Text(if (lockEnabled) "更改应用密码" else "设置应用密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lockEnabled) PasswordField("当前密码", current) { current = it }
                    PasswordField("新密码（4–12位数字）", next) { next = it }
                    PasswordField("再次输入新密码", confirm) { confirm = it }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    message = when {
                        lockEnabled && !AppLock.verify(context, current) -> "当前密码不正确"
                        !next.matches(Regex("\\d{4,12}")) -> "新密码需为4到12位数字"
                        next != confirm -> "两次新密码不一致"
                        else -> {
                            AppLock.setPin(context, next)
                            lockEnabled = true
                            biometricEnabled = false
                            configureLock = false
                            ""
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { configureLock = false }) { Text("取消") } }
        )
    }

    if (disableLock) {
        var current by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { disableLock = false },
            title = { Text("关闭密码锁") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField("当前密码", current) { current = it }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (AppLock.disable(context, current)) {
                        lockEnabled = false
                        biometricEnabled = false
                        disableLock = false
                    } else message = "密码不正确"
                }) { Text("确认关闭") }
            },
            dismissButton = { TextButton(onClick = { disableLock = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
