package com.localdoc.scanner.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.model.DocItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    items: List<DocItem>,
    onBack: () -> Unit,
    onRestore: (DocItem) -> Unit,
    onDeleteForever: (DocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var permanentTarget by remember { mutableStateOf<DocItem?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("回收站是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { doc ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(doc.title, style = MaterialTheme.typography.titleMedium)
                                Text("${doc.pageCount} 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRestore(doc) }) { Text("恢复") }
                            TextButton(onClick = { permanentTarget = doc }) { Text("永久删除") }
                        }
                    }
                }
            }
        }
    }

    permanentTarget?.let { doc ->
        AlertDialog(
            onDismissRequest = { permanentTarget = null },
            title = { Text("永久删除“${doc.title}”？") },
            text = { Text("原图、编辑结果和导出记录将从文档库删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { permanentTarget = null; onDeleteForever(doc) }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { permanentTarget = null }) { Text("取消") } }
        )
    }
}
