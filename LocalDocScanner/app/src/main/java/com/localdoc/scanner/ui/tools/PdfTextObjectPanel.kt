package com.localdoc.scanner.ui.tools
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.localdoc.scanner.pdf.PdfTextObject

@Composable
internal fun PdfTextObjectPanel(objects: List<PdfTextObject>, selected: String, replacement: String,
    onSelect: (PdfTextObject) -> Unit, onReplacement: (String) -> Unit) {
    var page by rememberSaveable(objects.firstOrNull()?.pageIndex) { mutableIntStateOf(0) }
    val count = ((objects.size + 19) / 20).coerceAtLeast(1)
    val current = page.coerceIn(0, count - 1)
    if (objects.isEmpty()) Text("未找到可修改的页级文字对象；扫描图片请使用OCR或填写文字。")
    if (count > 1) Row {
        TextButton({ page = current - 1 }, enabled = current > 0) { Text("上组") }
        Text("${current + 1}/$count 组")
        TextButton({ page = current + 1 }, enabled = current + 1 < count) { Text("下组") }
    }
    objects.drop(current * 20).take(20).forEach { item ->
        FilterChip(selected == item.key, { onSelect(item) }, label = { Text(item.text.ifBlank { item.reason }.take(80)) }, enabled = item.editable)
        if (!item.editable) Text(item.reason, style = MaterialTheme.typography.bodySmall)
    }
    objects.firstOrNull { it.key == selected }?.let {
        Text("原文：${it.text}")
        OutlinedTextField(replacement, onReplacement, label = { Text("替换内容，最多300字") }, modifier = Modifier.fillMaxWidth())
        Text("生成副本后检查真实页面；清空内容会保留原间距。此功能不是安全脱敏，请用永久打码删除敏感信息。", style = MaterialTheme.typography.bodySmall)
    }
}
