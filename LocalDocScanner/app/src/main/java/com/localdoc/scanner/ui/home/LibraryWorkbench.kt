package com.localdoc.scanner.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.localdoc.scanner.data.FullTextIndex
import com.localdoc.scanner.data.SearchHit
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryWorkbench(vm: AppViewModel, onBack: () -> Unit, onDoc: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val index = remember { FullTextIndex(context) }
    DisposableEffect(index) { onDispose { index.close() } }
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("扫描及导入/生成文件自动更新索引；也可手动增量核对，未变化文件会跳过。") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var unit by rememberSaveable { mutableStateOf("") }
    var rules by rememberSaveable { mutableStateOf("发票=发票\n合同=合同\n证件=身份证|护照|驾驶证\n收据=收据|小票") }
    var proposals by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val docs by vm.docs.collectAsState()
    LaunchedEffect(query, status) {
        if (query.isNotBlank() && !busy) {
            kotlinx.coroutines.delay(250)
            hits = withContext(Dispatchers.IO) { index.search(query) }
        } else hits = emptyList()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("全文检索与归档") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(status)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { scope.launch {
                    busy = true
                    val errors = withContext(Dispatchers.IO) {
                        index.prune(docs.map { it.id }.toSet())
                        docs.forEach(index::indexScan)
                        OutputHistoryStore.all(context).map { File(it.internalPath) to it.name }.filter { it.first.isFile }.distinctBy { it.first.absolutePath }.mapNotNull { (file, name) ->
                            runCatching { index.indexFile(file, name) }.exceptionOrNull()?.message
                        }
                    }
                    status = "索引已更新，${errors.size}个文件未纳入。" + errors.joinToString("\n")
                    busy = false
                } }, enabled = !busy) { Text("更新全部索引") }
                IndexImportActions(context) { status = it }
                OutlinedTextField(query, { query = it }, label = { Text("搜索PDF、Office、识别文字") }, modifier = Modifier.fillMaxWidth())
                Text("返回前100个匹配结果。原始图片需要先识别；支持旧Office文字提取。密码PDF在添加时显式解锁，索引仅存在本机。", style = MaterialTheme.typography.bodySmall)
            }
            items(hits, key = { it.key }) { hit -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                Text(hit.name, style = MaterialTheme.typography.titleSmall)
                Text(hit.snippet)
                TextButton(onClick = {
                    if (hit.key.startsWith("scan:")) onDoc(hit.key.removePrefix("scan:"))
                    else {
                        val file = File(hit.path)
                        com.localdoc.scanner.external.ExternalOpenBus.offer(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), OutputHistoryStore.mimeFor(file))
                        }, context.contentResolver)
                    }
                }) { Text("打开原文") }
            } } }
            item {
                HorizontalDivider()
                Text("扫描文档批量归档", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(unit, { unit = it; proposals = emptyList() }, label = { Text("单位/项目（可留空）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rules, { rules = it; proposals = emptyList() }, label = { Text("类型=关键词，多个关键词用 | 分开") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val parsed = rules.lines().mapNotNull { if ('=' in it) it.substringBefore('=').trim() to it.substringAfter('=').split('|').filter(String::isNotBlank) else null }
                    proposals = docs.map { doc ->
                        val text = doc.title + "\n" + doc.ocrText
                        val year = Regex("20\\d{2}").find(text)?.value ?: SimpleDateFormat("yyyy", Locale.US).format(Date(doc.createdAt))
                        val type = parsed.firstOrNull { (_, keywords) -> keywords.any { text.contains(it, true) } }?.first ?: "其他"
                        doc.id to listOf(year, unit.trim(), type).filter(String::isNotBlank).joinToString("/")
                    }
                }, enabled = !busy) { Text("预览归档建议") }
                proposals.forEach { (id, folder) -> Text("${docs.firstOrNull { it.id == id }?.title} → $folder") }
                if (proposals.isNotEmpty()) Button(onClick = { scope.launch {
                    busy = true
                    val targets = proposals.toList()
                    targets.forEach { (id, folder) -> vm.setOrganization(id, folder, docs.firstOrNull { it.id == id }?.tags.orEmpty()) }
                    status = "已归档${targets.size}份扫描文档；原文件内容保留。"
                    proposals = emptyList(); busy = false
                } }, enabled = !busy) { Text("应用以上归档建议") }
            }
        }
    }
}
