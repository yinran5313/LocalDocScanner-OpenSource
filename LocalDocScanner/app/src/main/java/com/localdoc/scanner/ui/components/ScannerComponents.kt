package com.localdoc.scanner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.R

@Composable
fun AppIcon(@DrawableRes resource: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), description, modifier.size(22.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerTopBar(title: String, onBack: () -> Unit, subtitle: String? = null) {
    TopAppBar(title = { Column { Text(title, style = MaterialTheme.typography.titleLarge); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
        navigationIcon = { IconButton(onClick = onBack) { AppIcon(R.drawable.ic_ui_back, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun ActionDock(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun SectionHeading(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        trailing()
    }
}

@Composable
fun InfoCard(title: String, detail: String, @DrawableRes icon: Int = R.drawable.ic_tool_document_scanner, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { AppIcon(icon) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TaskProgressCard(title: String, detail: String, completed: Int, total: Int, running: Boolean,
    canResume: Boolean, error: String = "", onPause: () -> Unit = {}, onResume: () -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { AppIcon(if (running) R.drawable.ic_ui_play else if (canResume) R.drawable.ic_ui_pause else R.drawable.ic_ui_check) }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text("$completed / $total 步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running) {
                if (total > 0 && completed > 0) LinearProgressIndicator(progress = { (completed.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (running) OutlinedButton(onClick = onPause, shape = MaterialTheme.shapes.small) { AppIcon(R.drawable.ic_ui_pause); Spacer(Modifier.width(6.dp)); Text("暂停，保留进度") }
            else if (canResume) FilledTonalButton(onClick = onResume, shape = MaterialTheme.shapes.small) { AppIcon(R.drawable.ic_ui_play); Spacer(Modifier.width(6.dp)); Text("继续处理") }
        }
    }
}
