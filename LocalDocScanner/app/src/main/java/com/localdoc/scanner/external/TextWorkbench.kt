package com.localdoc.scanner.external

import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
internal fun TextWorkbench(text: String, onTextChange: (String) -> Unit, extension: String,
    status: String, modifier: Modifier = Modifier) {
    var sourceMode by rememberSaveable(extension) { mutableStateOf(false) }
    val csv = extension in setOf("csv", "tsv")
    val markdown = extension in setOf("md", "markdown")
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (csv || markdown) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!sourceMode, { sourceMode = false }, label = { Text(if (csv) "表格编辑" else "阅读预览") })
            FilterChip(sourceMode, { sourceMode = true }, label = { Text("源码编辑") })
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        when {
            sourceMode || (!csv && !markdown) -> OutlinedTextField(text, onTextChange,
                modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("内容；保存会另存副本") })
            csv -> CsvGrid(text, onTextChange, extension == "tsv", Modifier.weight(1f))
            else -> MarkdownPreview(text, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
internal fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(context) { Markwon.create(context) }
    if (text.length > 500_000) Text("Markdown预览限50万字符，请使用源码查看或分段保存。", modifier)
    else AndroidView(factory = { context -> TextView(context).apply {
        textSize = 17f; setPadding(12, 8, 12, 16); setTextIsSelectable(true)
    } }, update = { view -> view.setTextColor(color); renderer.setMarkdown(view, text) }, modifier = modifier)
}

@Composable
private fun CsvGrid(text: String, onTextChange: (String) -> Unit, tabSeparated: Boolean, modifier: Modifier) {
    var delimiterIndex by rememberSaveable(tabSeparated) { mutableIntStateOf(if (tabSeparated) 1 else 0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var pageInput by rememberSaveable { mutableStateOf("1") }
    val delimiter = listOf(',', '\t', ';')[delimiterIndex]
    val parsed = remember(text, delimiter) { runCatching { CsvDocument.parse(text, delimiter) } }
    val document = parsed.getOrNull()
    fun commit(rows: List<List<String>>) { if (document != null) onTextChange(document.encode(rows)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("逗号", "制表符", "分号").forEachIndexed { i, label ->
                FilterChip(delimiterIndex == i, { delimiterIndex = i; page = 0 }, label = { Text(label) })
            }
        }
        if (document == null) { Text("无法显示表格：${parsed.exceptionOrNull()?.message}。可切换源码编辑。", color = MaterialTheme.colorScheme.error); return@Column }
        val rows = document.rows
        val columns = rows.maxOfOrNull { it.size } ?: 1
        val pages = ((rows.size + 19) / 20).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        Text("${rows.size}行 · $columns 列；单元格按文本保存，保留前导零和公式原文。", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton({ page = current - 1 }, enabled = current > 0) { Text("上页") }
            OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit).take(5) }, label = { Text("${current + 1}/$pages 页") }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton({ pageInput.toIntOrNull()?.takeIf { it in 1..pages }?.let { page = it - 1 } }) { Text("跳转") }
            TextButton({ page = current + 1 }, enabled = current + 1 < pages) { Text("下页") }
        }
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.drop(current * 20).take(20).forEachIndexed { offset, row ->
                val index = current * 20 + offset
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}", Modifier.width(32.dp))
                    repeat(columns) { column ->
                        OutlinedTextField(row.getOrElse(column) { "" }, { value ->
                            val cells = row.toMutableList().apply { while (size <= column) add(""); set(column, value) }
                            commit(rows.mapIndexed { i, old -> if (i == index) cells else old })
                        }, modifier = Modifier.width(160.dp), label = { Text("列${column + 1}") })
                    }
                    TextButton(onClick = { commit(rows.filterIndexed { i, _ -> i != index }) }) { Text("删除行") }
                }
            }
        }
        Row {
            TextButton(onClick = { commit(rows + listOf(List(columns) { "" })); page = rows.size / 20 }, enabled = (rows.size + 1) * columns <= 20_000) { Text("新增行") }
            TextButton(onClick = { commit(if (rows.isEmpty()) listOf(listOf("")) else rows.map { it + List((columns + 1 - it.size).coerceAtLeast(1)) { "" } }) }, enabled = columns < 200 && rows.size * (columns + 1) <= 20_000) { Text("新增列") }
        }
    }
}
