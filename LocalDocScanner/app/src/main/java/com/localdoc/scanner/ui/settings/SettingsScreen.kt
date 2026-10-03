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
    var officeEngine by remember { mutableStateOf(OfficeEngineBridge.installed(context)) }
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
                        TextButton(onClick = { disableLock = true }, modifier = Modifier.fillMaxWidth()) { Text("关闭密码锁") }
                    }
                }
            }
            Text("版本 4.3.0 RC1", style = MaterialTheme.typography.labelMedium)
        }
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
