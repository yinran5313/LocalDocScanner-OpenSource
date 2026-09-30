package com.localdoc.scanner.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.data.DraftPage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    pages: List<DraftPage>,
    title: String,
    isAppending: Boolean,
    onTitleChange: (String) -> Unit,
    onBackHome: () -> Unit,
    onAddCamera: () -> Unit,
    onAddImages: () -> Unit,
    onEdit: (DraftPage, Int) -> Unit,
    onDelete: (DraftPage) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isAppending) "添加页面" else "整理文档")
                        Text("${pages.size} 页 · 草稿会自动保留", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBackHome) { Text("首页") } }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAddCamera, modifier = Modifier.weight(1f)) { Text("继续拍照") }
                    TextButton(onClick = onAddImages, modifier = Modifier.weight(1f)) { Text("导入图片") }
                }
                Button(onClick = onSave, enabled = pages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (isAppending) "加入原文档" else "完成并保存到文档库")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isAppending) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("文档名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有页面，可以继续拍照或导入图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                        Card {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                                    AsyncImage(
                                        model = File(page.renderedPath),
                                        contentDescription = "第 ${index + 1} 页",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "${index + 1}",
                                        modifier = Modifier.padding(6.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Text("前移") }
                                    TextButton(onClick = { onMove(index, index + 1) }, enabled = index < pages.lastIndex) { Text("后移") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    TextButton(onClick = { onEdit(page, index) }) { Text("重编") }
                                    TextButton(onClick = { onDelete(page) }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
