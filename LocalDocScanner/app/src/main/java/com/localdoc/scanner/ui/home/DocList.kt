package com.localdoc.scanner.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.R
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.ui.components.AppIcon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentDocumentRow(doc: DocItem, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(width = 60.dp, height = 76.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (doc.coverPath != null) AsyncImage(File(doc.coverPath), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                    else AppIcon(R.drawable.ic_tool_picture_as_pdf)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text((if (doc.favorite) "★ " else "") + doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${doc.pageCount} 页 · ${SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date(doc.updatedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val organization = listOfNotNull(doc.folder, doc.tags.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (organization.isNotBlank()) Text(organization, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMenu) { AppIcon(R.drawable.ic_ui_more, "${doc.title}的操作菜单") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridCard(doc: DocItem, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.combinedClickable(onClick = onClick, onLongClick = onMenu), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(Modifier.fillMaxWidth().height(132.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                Box(contentAlignment = Alignment.Center) {
                    if (doc.coverPath != null) AsyncImage(File(doc.coverPath), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                    else AppIcon(R.drawable.ic_tool_picture_as_pdf)
                }
            }
            Text((if (doc.favorite) "★ " else "") + doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${doc.pageCount} 页", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onMenu, Modifier.size(36.dp)) { AppIcon(R.drawable.ic_ui_more, "文档操作") }
            }
        }
    }
}
