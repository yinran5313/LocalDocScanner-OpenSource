package com.localdoc.scanner.ui.tools
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.localdoc.scanner.ocr.OcrLanguage
@Composable
internal fun OcrLanguagePicker(code: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val language = OcrLanguage.fromCode(code)
    Box { OutlinedButton({ expanded = true }) { Text("语言：${language.label}") }
        DropdownMenu(expanded, { expanded = false }) { OcrLanguage.entries.forEach { item ->
            DropdownMenuItem(text = { Text(item.label) }, onClick = { onChange(item.name); expanded = false })
        } }
    }
    Text(if (language.requiresMedium) "日文自动使用高精度 medium。" else "使用内置多语言模型，混排文字也会保留；语言选择不会删除其他语言文字。", style = MaterialTheme.typography.bodySmall)
}
