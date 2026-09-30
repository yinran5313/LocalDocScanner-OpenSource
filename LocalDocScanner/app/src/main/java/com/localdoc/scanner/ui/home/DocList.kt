package com.localdoc.scanner.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.model.DocItem
import java.io.File

private const val DOC_COLUMNS = 2

@Composable
fun DocRow(
    items: List<DocItem>,
    onDocClick: (DocItem) -> Unit,
    onDocLongClick: (DocItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { doc ->
            DocCard(
                doc = doc,
                modifier = Modifier.weight(1f),
                onClick = { onDocClick(doc) },
                onLongClick = { onDocLongClick(doc) }
            )
        }
        repeat(DOC_COLUMNS - items.size) { Spacer(modifier = Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(doc: DocItem, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (doc.coverPath != null) {
                    AsyncImage(
                        model = File(doc.coverPath),
                        contentDescription = doc.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("${doc.pageCount} 页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(doc.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(doc.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val organization = listOfNotNull(doc.folder, doc.tags.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (organization.isNotBlank()) {
                    Text(
                        organization,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
