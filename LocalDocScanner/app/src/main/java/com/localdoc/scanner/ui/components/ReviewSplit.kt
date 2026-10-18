package com.localdoc.scanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Source and editable result share the screen; the divider adjusts their space. */
@Composable
internal fun ReviewSplit(modifier:Modifier=Modifier,source:@Composable (Modifier)->Unit,fields:@Composable (Modifier)->Unit) {
    var fraction by rememberSaveable { mutableFloatStateOf(.4f) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide=maxWidth>=600.dp && maxWidth>maxHeight
        val pixels=with(LocalDensity.current) { (if(sideBySide)maxWidth else maxHeight).toPx() }
        val drag=Modifier.testTag("review-divider").pointerInput(sideBySide,pixels) {
            detectDragGestures { change,amount ->
                fraction=(fraction+(if(sideBySide)amount.x else amount.y)/pixels.coerceAtLeast(1f)).coerceIn(.22f,.65f)
                change.consume()
            }
        }
        if(sideBySide) Row(Modifier.fillMaxSize()) {
            source(Modifier.weight(fraction).fillMaxHeight())
            Box(drag.width(28.dp).fillMaxHeight(),contentAlignment=Alignment.Center) { Box(Modifier.width(4.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant,MaterialTheme.shapes.small)) }
            fields(Modifier.weight(1f-fraction).fillMaxHeight())
        } else Column(Modifier.fillMaxSize()) {
            source(Modifier.weight(fraction).fillMaxWidth())
            Box(drag.height(28.dp).fillMaxWidth(),contentAlignment=Alignment.Center) { Box(Modifier.height(4.dp).width(48.dp).background(MaterialTheme.colorScheme.outlineVariant,MaterialTheme.shapes.small)) }
            fields(Modifier.weight(1f-fraction).fillMaxWidth())
        }
    }
}
