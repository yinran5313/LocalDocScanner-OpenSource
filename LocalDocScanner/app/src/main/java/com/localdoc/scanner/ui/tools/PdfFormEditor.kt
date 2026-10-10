package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.PdfFormField
import com.localdoc.scanner.ui.components.WrappingOptions

@Composable
internal fun PdfFormEditor(fields: List<PdfFormField>, values: Map<String, String>, onChange: (String, String) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = ((fields.size + 19) / 20).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pages - 1)
    WrappingOptions {
        TextButton(onClick = { page-- }, enabled = currentPage > 0) { Text("前20项") }
        Text("${fields.size}项 · ${currentPage + 1}/$pages")
        TextButton(onClick = { page++ }, enabled = currentPage + 1 < pages) { Text("后20项") }
    }
    fields.drop(currentPage * 20).take(20).forEach { field ->
        val value = values[field.name] ?: field.value
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.name + if (field.readOnly) "（只读）" else "")
            when {
                field.type == "PDCheckBox" -> Row {
                    Checkbox(value != "Off" && value.isNotBlank(), { onChange(field.name, if (it) field.options.firstOrNull() ?: "Yes" else "Off") }, enabled = !field.readOnly)
                    Text("勾选")
                }
                field.options.isNotEmpty() -> field.options.forEachIndexed { index, option ->
                    val selected = if (field.multiSelect) option in value.split('\u001f') else value == option
                    FilterChip(selected, {
                        val next = if (field.multiSelect) {
                            val set = value.split('\u001f').filter(String::isNotBlank).toMutableSet()
                            if (selected) set.remove(option) else set.add(option)
                            set.joinToString("\u001f")
                        } else option
                        onChange(field.name, next)
                    }, enabled = !field.readOnly, label = { Text(field.optionLabels.getOrNull(index) ?: option) })
                }
                field.type == "PDSignatureField" -> Text("此字段需证书签名，不能填写普通文字")
                else -> OutlinedTextField(value, { onChange(field.name, it) }, enabled = !field.readOnly,
                    label = { Text(field.type) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
