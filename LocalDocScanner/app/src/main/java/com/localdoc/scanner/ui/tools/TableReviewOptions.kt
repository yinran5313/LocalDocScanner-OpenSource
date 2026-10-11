package com.localdoc.scanner.ui.tools
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.ReviewPage
import com.localdoc.scanner.structure.*

@Composable
internal fun TableReviewOptions(page: ReviewPage, onChange: (ReviewPage) -> Unit) {
    var range by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var column by remember { mutableStateOf("1") }
    var typesOpen by remember { mutableStateOf(false) }
    val merges = page.merges.orEmpty()
    Text("合并格与列类型", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(range, { range=it }, label={ Text("范围：起始行:列-结束行:列，例如 1:1-1:3") }, singleLine=true, modifier=Modifier.fillMaxWidth())
    Row {
        TextButton(onClick={ runCatching {
            val values=Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$").matchEntire(range.trim())?.groupValues?.drop(1)?.map { it.toInt()-1 } ?: error("请输入 行:列-行:列")
            val merge=CellMerge(values[0],values[1],values[2],values[3])
            TableLayout.validate(page.rows, merges+merge)
            onChange(page.copy(merges=merges+merge)); range=""; error=""
        }.onFailure { error=it.message.orEmpty() } }) { Text("合并所选范围") }
        if (merges.isNotEmpty()) TextButton(onClick={ onChange(page.copy(merges=emptyList())) }) { Text("拆分全部合并格") }
    }
    merges.forEach { m -> TextButton(onClick={ onChange(page.copy(merges=merges-m)) }) { Text("${m.top+1}:${m.left+1}-${m.bottom+1}:${m.right+1} · 点此拆分") } }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(column,{ column=it.filter(Char::isDigit).take(3) },label={ Text("第几列") },modifier=Modifier.width(100.dp),singleLine=true)
        Box { OutlinedButton({ typesOpen=true }) { Text("设置列类型") }
            DropdownMenu(typesOpen,{ typesOpen=false }) { listOf("TEXT" to "文本/号码", "NUMBER" to "数值", "DATE" to "日期", "PERCENT" to "百分比").forEach { (type,label) ->
                DropdownMenuItem(text={ Text(label) },onClick={
                    val c=(column.toIntOrNull() ?: 0)-1
                    if(c in 0 until (page.rows.maxOfOrNull { it.size } ?: 0)) { onChange(page.copy(columnTypes=page.columnTypes.orEmpty()+(c to type))); error="" } else error="列号超出范围"
                    typesOpen=false
                })
            } }
        }
    }
    if(page.columnTypes.orEmpty().isNotEmpty()) Text(page.columnTypes.orEmpty().entries.sortedBy { it.key }.joinToString(" · ") { "第${it.key+1}列 ${it.value}" },style=MaterialTheme.typography.bodySmall)
    Text("合并格内容合到左上角；长号码保留文本。PDF中不存在的Excel公式不会自动生成。",style=MaterialTheme.typography.bodySmall)
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
}
