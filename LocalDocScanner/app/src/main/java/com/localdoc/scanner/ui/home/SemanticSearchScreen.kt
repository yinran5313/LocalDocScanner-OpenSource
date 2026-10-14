package com.localdoc.scanner.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.data.SearchHit
import com.localdoc.scanner.models.SemanticSearch
import com.localdoc.scanner.models.ModelsScreen
import com.localdoc.scanner.ui.components.ScannerTopBar
import kotlinx.coroutines.launch

@Composable
fun SemanticSearchScreen(onBack: () -> Unit, onOpen: (SearchHit) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<SearchHit, Float>>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var models by remember { mutableStateOf(false) }
    if (models) { ModelsScreen(onBack = { models = false }); return }
    Scaffold(topBar = { ScannerTopBar("按意思找文档", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("输入想找的内容，例如“报销需要哪些材料”。结果来自本机文字索引，点开可以核对原文。首次检索会建立缓存，返回或取消会停止计算。", style = MaterialTheme.typography.bodySmall)
                Text("本版每份最多检索前10万字符，一次最多800万字符；未OCR的图片型文件需要先识别。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it.take(1000) }, label = { Text("描述要找的内容") }, modifier = Modifier.fillMaxWidth())
                Row {
                    Button({ job = scope.launch {
                        status = "正在计算，可取消…"
                        try { results = SemanticSearch.search(context, query); status = "找到${results.size}份候选；相似度不是正确率" }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) { status = "已取消，已完成的缓存保留"; throw e }; status = e.message.orEmpty() }
                        finally { job = null }
                    } }, enabled = query.isNotBlank() && job == null) { Text("检索") }
                    if (job != null) TextButton({ job?.cancel() }) { Text("取消") }
                    TextButton({ job?.cancel(); models = true }) { Text("模型管理") }
                }
                if (job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status)
            }
            items(results, key = { it.first.key }) { (hit, score) ->
                Card(onClick = { onOpen(hit) }) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(hit.name, style = MaterialTheme.typography.titleSmall)
                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    Text("相似度 ${"%.2f".format(score)} · 点开原文", style = MaterialTheme.typography.labelSmall)
                } }
            }
        }
    }
}
