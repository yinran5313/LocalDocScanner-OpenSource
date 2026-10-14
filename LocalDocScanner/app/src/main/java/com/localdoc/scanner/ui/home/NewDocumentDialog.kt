package com.localdoc.scanner.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun NewDocumentDialog(onDismiss: () -> Unit, onCreate: suspend (String, String) -> Unit) {
    var extension by rememberSaveable { mutableStateOf("docx") }
    var name by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建文档") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("docx" to "Word 文档", "xlsx" to "Excel 表格", "pptx" to "PPT 演示文稿", "md" to "Markdown 文档").forEach { (type, label) ->
                FilterChip(extension == type, { extension = type }, label = { Text(label) })
            }
            OutlinedTextField(name, { name = it }, label = { Text("名称（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("创建后保存到文档库，点开即可编辑、另存和分享。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ submitting = true; scope.launch { try { onCreate(extension, name.ifBlank { "未命名文档" }) } finally { submitting = false } } }, enabled = !submitting) { Text(if (submitting) "创建中…" else "创建") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
