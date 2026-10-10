package com.localdoc.scanner.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

enum class ActionStyle { FILLED, OUTLINED, TEXT }
data class DockAction(val label: String, val onClick: () -> Unit, val icon: Int? = null,
    val style: ActionStyle = ActionStyle.FILLED, val enabled: Boolean = true)

/** Measure the actual translated labels with the active font and scale, including padding/icons.
 * Equal columns if they fit, omit decorative icons next, stack only when labels cannot fit. */
@Composable
fun AdaptiveActions(actions: List<DockAction>, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slot = (maxWidth - 10.dp * (actions.size - 1)) / actions.size
        val labelWidths = actions.map { with(density) { measure.measure(it.label, textStyle, maxLines = 1).size.width.toDp() } }
        val showIcons = actions.indices.all { labelWidths[it] + 32.dp + (if (actions[it].icon != null) 28.dp else 0.dp) <= slot }
        val stacked = labelWidths.any { it + 32.dp > slot }
        @Composable fun action(item: DockAction, buttonModifier: Modifier) {
            val content: @Composable RowScope.() -> Unit = {
                if (showIcons && item.icon != null) { AppIcon(item.icon); Spacer(Modifier.width(6.dp)) }
                Text(item.label)
            }
            val padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            when (item.style) {
                ActionStyle.FILLED -> Button(item.onClick, buttonModifier.heightIn(min = 52.dp), enabled = item.enabled, contentPadding = padding, content = content)
                ActionStyle.OUTLINED -> OutlinedButton(item.onClick, buttonModifier.heightIn(min = 52.dp), enabled = item.enabled, contentPadding = padding, content = content)
                ActionStyle.TEXT -> TextButton(item.onClick, buttonModifier.heightIn(min = 48.dp), enabled = item.enabled, contentPadding = padding, content = content)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { actions.forEach { action(it, Modifier.fillMaxWidth()) } }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions.forEach { action(it, Modifier.weight(1f)) } }
    }
}

/** Options and secondary actions wrap rather than squeezing labels or clipping the last item. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrappingOptions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}
