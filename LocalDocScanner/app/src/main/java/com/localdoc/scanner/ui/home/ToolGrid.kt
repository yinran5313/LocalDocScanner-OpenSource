package com.localdoc.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import com.localdoc.scanner.ui.components.AppIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import com.localdoc.scanner.R
import com.localdoc.scanner.model.TOOL_ENTRIES
import com.localdoc.scanner.model.ToolEntry

private val toolsById = TOOL_ENTRIES.associateBy { it.id }

private data class ToolGroup(val id: String, val title: String, val toolIds: List<String>)

private val TOOL_GROUPS = listOf(
    ToolGroup("image", "图片处理", listOf("image_edit", "long_image", "images_to_pdf")),
    ToolGroup(
        "pdf",
        "PDF 处理",
        listOf("pdf_office", "pdf_sign", "pdf_merge", "pdf_split", "pdf_compress", "pdf_to_images", "pdf_text", "pdf_encrypt", "pdf_compare")
    ),
    ToolGroup("recognize", "识别与提取", listOf("ocr", "card", "barcode")),
    ToolGroup("structure", "票据与表格", listOf("batch_extract", "table_xlsx"))
)

@Composable
fun ToolGrid(onToolClick: (ToolEntry) -> Unit, modifier: Modifier = Modifier) {
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val byId = toolsById

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TOOL_GROUPS.forEach { group ->
            val tools = group.toolIds.mapNotNull(byId::get)
            val expanded = expandedGroup == group.id
            Card(
                onClick = { expandedGroup = if (expanded) null else group.id },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = MaterialTheme.shapes.medium, color = if (group.id == "pdf") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { AppIcon(toolIcon(tools.first().id)) }
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(group.toolIds.take(2).mapNotNull(byId::get).joinToString(" · ") { it.label } + "  /  ${tools.size}项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                        contentDescription = if (expanded) "收起${group.title}" else "展开${group.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                ToolRows(tools = tools, onToolClick = onToolClick)
            }
        }
    }
}

@Composable
private fun ToolRows(tools: List<ToolEntry>, onToolClick: (ToolEntry) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = (maxWidth.value / (104f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 5)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        tools.chunked(columns).forEach { rowTools ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowTools.forEach { tool ->
                    ToolCard(tool = tool, modifier = Modifier.weight(1f), onClick = { onToolClick(tool) })
                }
                repeat(columns - rowTools.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
    }
}

@Composable
private fun ToolCard(tool: ToolEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(toolIcon(tool.id)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(25.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(tool.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@DrawableRes
internal fun toolIcon(id: String): Int = when (id) {
    "images_to_pdf" -> R.drawable.ic_tool_picture_as_pdf
    "pdf_merge" -> R.drawable.ic_tool_merge
    "pdf_split" -> R.drawable.ic_tool_content_cut
    "pdf_compress" -> R.drawable.ic_tool_compress
    "pdf_to_images" -> R.drawable.ic_tool_imagesmode
    "pdf_text" -> R.drawable.ic_tool_text_snippet
    "pdf_encrypt" -> R.drawable.ic_tool_lock
    "pdf_office" -> R.drawable.ic_tool_tune
    "pdf_sign" -> R.drawable.ic_tool_tune
    "ocr" -> R.drawable.ic_tool_document_scanner
    "card" -> R.drawable.ic_tool_badge
    "barcode" -> R.drawable.ic_tool_qr_code_scanner
    "long_image" -> R.drawable.ic_tool_view_day
    else -> R.drawable.ic_tool_tune
}
