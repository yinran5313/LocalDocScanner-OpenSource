package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest

import com.localdoc.scanner.ui.components.*

@Composable
internal fun PdfCompareFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, config = {
        Text("选择两份PDF；按页码对齐，左侧原稿、右侧对照稿，差异标红。渲染差异也可能来自字体或排版变化。")
    })
}


@Composable
internal fun PdfMergeFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var order by rememberToolState(request, "order") { request.files.toList() }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("合并顺序（可调整）", style = MaterialTheme.typography.titleSmall)
            order.forEachIndexed { index, file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${index + 1}. ${file.name}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (index > 0) {
                            val m = order.toMutableList()
                            val t = m[index - 1]
                            m[index - 1] = m[index]
                            m[index] = t
                            order = m
                        }
                    }) { Text("↑") }
                    TextButton(onClick = {
                        if (index < order.lastIndex) {
                            val m = order.toMutableList()
                            val t = m[index + 1]
                            m[index + 1] = m[index]
                            m[index] = t
                            order = m
                        }
                    }) { Text("↓") }
                }
            }
            Text("合并会保留原页面尺寸和可选文字层。", style = MaterialTheme.typography.bodySmall)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "合并", modifier = modifier, config = { panel() }
    )
}



@Composable
internal fun PdfSplitFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var mode by rememberToolState(request, "mode") { 0 }
    var perFile by rememberToolState(request, "perFile") { "1" }
    var ranges by rememberToolState(request, "ranges") { "1-1" }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("拆分方式", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("每份 N 页", "自定义范围"), mode) { mode = it }
            if (mode == 0) {
                OutlinedTextField(
                    value = perFile, onValueChange = { perFile = it.filter { c -> c.isDigit() } },
                    label = { Text("每份页数") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = ranges, onValueChange = { ranges = it },
                    label = { Text("页范围，如 1-3,5,8-10") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Text("拆分会保留原页面内容和可选文字层。", style = MaterialTheme.typography.bodySmall)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "拆分", modifier = modifier, config = { panel() }
    )
}



@Composable
internal fun PdfCompressFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var tier by rememberToolState(request, "tier") { 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("压缩档位", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("微信", "邮件", "打印", "高清归档"), tier) { tier = it }
            Text("仅压缩图片，保留可搜索文字、表单和批注。图片清晰度会降低；已经较小的文件保持原样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "压缩", modifier = modifier, config = { panel() }
    )
}



@Composable
internal fun PdfToImagesFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val src = request.files.first()
    var dpi by rememberToolState(request, "dpi") { 1 }

    @Composable
    fun panel() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导出清晰度", style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("高(200dpi)", "中(150dpi)", "低(110dpi)"), dpi) { dpi = it }
        }
    }

    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "导出图片", modifier = modifier, config = { panel() }
    )
}


@Composable
internal fun PdfTextFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    val clipboard = LocalClipboardManager.current
    ToolFlow(
        request = request,
        vm = vm,
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        runLabel = "提取文字",
        modifier = modifier,
        resultExtra = { outcome ->
            val text = outcome.copyText.orEmpty()
            if (text.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制全部") }
            }
        },
        config = {
            Text("原生文字PDF会直接保留文字顺序；扫描图片PDF没有文字层时，请改用“文字识别”。")
        }
    )
}


@Composable
internal fun PdfEncryptFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ToolFlow(
        request = request,
        vm = vm,
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        runLabel = "生成加密副本",
        modifier = modifier,
        taskPassword = { require(password.isNotBlank()) { "密码不能为空" }; require(password == confirm) { "两次密码不一致" }; password.toCharArray() },
        config = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("打开密码") }, singleLine = true)
                OutlinedTextField(confirm, { confirm = it }, label = { Text("再次输入密码") }, singleLine = true)
                Text("原PDF不会被覆盖；忘记密码后本App无法恢复。任务重开续跑需重新输入密码。", style = MaterialTheme.typography.bodySmall)
            }
        }
    )
}
