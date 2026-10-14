package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.components.ScannerTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardWalletScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val docs by vm.docs.collectAsState()
    var query by remember { mutableStateOf("") }
    val cards = docs.filter { it.folder == "证件卡包" && (query.isBlank() || it.title.contains(query, true) || it.tags.contains(query, true)) }
    Scaffold(topBar = { ScannerTopBar("证件卡包", onBack, "本机保存 · 包含在文档库备份中") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Button(onAdd, Modifier.fillMaxWidth()) { Text("添加证件 · 选择正反面") } }
            item { OutlinedTextField(query, { query = it }, label = { Text("姓名、标题或证件类型") }, modifier = Modifier.fillMaxWidth()) }
            item { Text("先裁好正反面 → 核对字段 → 确认证件类型 → 存入卡包。点证件可预览、分享；重命名和移出卡包在文档库长按办理。", style = MaterialTheme.typography.bodySmall) }
            if (cards.isEmpty()) item { Text("暂无匹配证件") }
            items(cards, key = { it.id }) { card ->
                OutlinedCard(onClick = { onOpen(card.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text(card.title, style = MaterialTheme.typography.titleMedium); Text(card.tags, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
