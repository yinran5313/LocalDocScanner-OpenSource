package com.localdoc.scanner.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.model.ToolEntry
import com.localdoc.scanner.model.toolGuidance

@Composable
internal fun ToolGuide(tool: ToolEntry) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val guide=remember(tool) { toolGuidance(tool) }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(guide.purpose,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({expanded=!expanded},contentPadding=PaddingValues(horizontal=0.dp,vertical=2.dp)) { Text(if(expanded) "收起操作步骤" else "查看操作步骤 · ${guide.steps.size}步") }
        if(expanded) guide.steps.forEachIndexed { index,step -> Text("${index+1}. $step",style=MaterialTheme.typography.bodyMedium) }
    }
}
