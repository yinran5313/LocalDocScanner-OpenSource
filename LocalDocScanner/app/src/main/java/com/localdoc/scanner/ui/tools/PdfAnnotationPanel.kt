package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.PdfAnnotationEntry

@Composable
internal fun PdfAnnotationPanel(entries: List<PdfAnnotationEntry>, selected: String,
    contents: String, delete: Boolean, onSelect: (PdfAnnotationEntry) -> Unit,
    onContents: (String) -> Unit, onDelete: (Boolean) -> Unit) {
    Text("选择本页现有批注", style = MaterialTheme.typography.titleSmall)
    if (entries.isEmpty()) Text("本页没有独立批注；已经烧入页面的文字或签名无法在这里移除。")
    entries.forEachIndexed { index, entry ->
        FilterChip(selected == entry.key, { onSelect(entry) }, label = {
            Text("${index + 1}. ${entry.type} · ${entry.contents.ifBlank { "无备注" }.take(55)}")
        }, enabled = entry.editable)
        if (!entry.editable) Text(entry.reason, style = MaterialTheme.typography.bodySmall)
    }
    entries.firstOrNull { it.key == selected && it.editable }?.let {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("移除此批注", Modifier.weight(1f)); Switch(delete, onDelete)
        }
        if (!delete) {
            OutlinedTextField(contents, onContents, label = { Text("批注备注（最多1000字）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("${contents.length}/1000", color = if (contents.length > 1000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (delete) "生成副本时移除所选批注；原PDF仍保留。" else "修改便签内容或高亮、笔迹附带的备注，保留原标记形状。", style = MaterialTheme.typography.bodySmall)
    }
}
