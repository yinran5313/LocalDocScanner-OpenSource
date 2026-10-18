package com.localdoc.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
    ToolGroup("capture", "拍摄", listOf("scan_ppt", "scan_whiteboard", "scan_handwriting", "watermark_camera")),
    ToolGroup("image", "图片与书籍", listOf("image_edit", "photo_restore", "region_erase", "portrait", "book_unwarp", "count_objects", "long_image", "images_to_pdf", "image_ppt")),
    ToolGroup(
        "pdf",
        "PDF 处理",
        listOf("pdf_office", "pdf_pages", "sign_library", "pdf_sign", "pdf_merge", "pdf_split", "pdf_compress", "pdf_to_images", "pdf_long", "pdf_enhance", "pdf_text", "pdf_encrypt", "pdf_compare")
    ),
    ToolGroup("recognize", "识别与提取", listOf("ocr", "formula", "translate", "structured_markdown", "card", "card_wallet", "barcode_live", "barcode", "qr_create")),
    ToolGroup("structure", "票据与表格", listOf("batch_extract", "table_xlsx")),
    ToolGroup("files", "文件管理", listOf("zip_import", "semantic_search"))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolGrid(onToolClick: (ToolEntry) -> Unit, modifier: Modifier = Modifier) {
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val byId = toolsById
    var query by rememberSaveable { mutableStateOf("") }
    val recent by com.localdoc.scanner.data.rememberPreference("recentTools", "")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索工具与用途")},leadingIcon={AppIcon(R.drawable.ic_ui_search)})
        if(query.isBlank()) {
            val recentTools=recent.split(',').mapNotNull(byId::get).take(4)
            if(recentTools.isNotEmpty()) { Text("最近使用",style=MaterialTheme.typography.titleSmall); CompactToolRows(recentTools,onToolClick) }
        }
        if(query.isNotBlank() && TOOL_ENTRIES.none { it.label.contains(query,true) || com.localdoc.scanner.model.toolGuidance(it).purpose.contains(query,true) }) Text("没有找到工具，试试“签名”“票据”或“图片”。")
        TOOL_GROUPS.forEach { group ->
            val tools = group.toolIds.mapNotNull(byId::get).filter { query.isBlank() || it.label.contains(query,true) || com.localdoc.scanner.model.toolGuidance(it).purpose.contains(query,true) }
            if(tools.isEmpty()) return@forEach
            val expanded = query.isNotBlank() || group.id !in collapsedGroups
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { collapsedGroups = if (expanded) collapsedGroups + group.id else collapsedGroups - group.id }), verticalAlignment = Alignment.CenterVertically) {
                Text(group.title, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right), if (expanded) "收起" else "展开", Modifier.size(24.dp))
            }
            AnimatedVisibility(visible = expanded) {
                CompactToolRows(tools = tools, onToolClick = onToolClick)
            }
        }
    }
}

@Composable
internal fun CompactToolRows(tools: List<ToolEntry>, onToolClick: (ToolEntry) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = (maxWidth.value / (78f * fontScale.coerceIn(1f, 1.5f))).toInt().coerceIn(1, 4)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(tool: ToolEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var help by androidx.compose.runtime.remember { mutableStateOf(false) }
    val color = when {
        tool.id.startsWith("pdf") -> androidx.compose.ui.graphics.Color(0xFF357DE8)
        tool.id in listOf("batch_extract", "table_xlsx", "card") -> androidx.compose.ui.graphics.Color(0xFF009E88)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = { help = true }).padding(horizontal = 3.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(toolIcon(tool.id)), null, Modifier.size(32.dp), tint = color)
        Spacer(Modifier.height(8.dp))
        Text(shortToolLabel(tool), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(tool.label) },
        text = { Text(toolHelp(tool)) }, confirmButton = { TextButton({ help = false; onClick() }) { Text("开始") } },
        dismissButton = { TextButton({ help = false }) { Text("关闭") } })
}

internal fun shortToolLabel(tool: ToolEntry) = when (tool.id) {
    "batch_extract" -> "票据汇总"
    "table_xlsx" -> "转表格"
    "pdf_sign" -> "证书签名"
    "pdf_text" -> "提取文字"
    "pdf_encrypt" -> "加密码"
    else -> tool.label
}

internal fun toolHelp(tool: ToolEntry) = com.localdoc.scanner.model.toolGuidance(tool).text

@DrawableRes
internal fun toolIcon(id: String): Int = when (id) {
    "images_to_pdf" -> R.drawable.ic_tool_picture_as_pdf
    "pdf_merge" -> R.drawable.ic_tool_merge
    "pdf_split" -> R.drawable.ic_tool_content_cut
    "pdf_compress" -> R.drawable.ic_tool_compress
    "pdf_to_images" -> R.drawable.ic_tool_imagesmode
    "pdf_text" -> R.drawable.ic_tool_text_snippet
    "pdf_encrypt" -> R.drawable.ic_tool_lock
    "pdf_office" -> R.drawable.ic_tool_edit
    "batch_extract" -> R.drawable.ic_tool_receipt
    "table_xlsx" -> R.drawable.ic_tool_grid
    "pdf_sign" -> R.drawable.ic_tool_verified_user
    "portrait" -> R.drawable.ic_tool_badge
    "count_objects" -> R.drawable.ic_tool_imagesmode
    "watermark_camera", "scan_ppt", "scan_whiteboard", "scan_handwriting" -> R.drawable.ic_ui_camera
    "book_unwarp" -> R.drawable.ic_tool_auto_stories
    "structured_markdown" -> R.drawable.ic_tool_text_snippet
    "translate" -> R.drawable.ic_tool_text_snippet
    "qr_create" -> R.drawable.ic_tool_qr_code_scanner
    "barcode_live" -> R.drawable.ic_tool_qr_code_scanner
    "pdf_long" -> R.drawable.ic_tool_view_day
    "pdf_pages" -> R.drawable.ic_tool_imagesmode
    "zip_import" -> R.drawable.ic_ui_folder
    "home_import" -> R.drawable.ic_ui_folder
    "home_new" -> R.drawable.ic_tool_text_snippet
    "semantic_search" -> R.drawable.ic_ui_search
    "ocr" -> R.drawable.ic_tool_document_scanner
    "card" -> R.drawable.ic_tool_badge
    "card_wallet" -> R.drawable.ic_tool_wallet
    "formula" -> R.drawable.ic_tool_functions
    "sign_library" -> R.drawable.ic_tool_draw
    "photo_restore", "region_erase", "pdf_enhance" -> R.drawable.ic_tool_auto_fix_high
    "image_ppt" -> R.drawable.ic_tool_imagesmode
    "barcode" -> R.drawable.ic_tool_qr_code_scanner
    "long_image" -> R.drawable.ic_tool_view_day
    else -> R.drawable.ic_tool_tune
}
