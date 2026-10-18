package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.localdoc.scanner.cv.*
import com.localdoc.scanner.ui.components.rememberDisplayBitmap
import com.localdoc.scanner.util.ImageIo
import java.io.File

@Composable
internal fun FilterPresetRow(source:File,selected:ScanFilter,onSelect:(ScanFilter)->Unit) {
    LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        items(ScanFilter.entries,key={it.name}) {filter ->
            val image by rememberDisplayBitmap(source to filter) {
                val input=ImageIo.loadFromFile(source,180) ?: return@rememberDisplayBitmap null
                try {val output=applyFilter(input,filter);if(output!==input) input.recycle();output}
                catch(e:Exception) {input.recycle();throw e}
            }
            Surface(Modifier.width(78.dp).clickable {onSelect(filter)},shape=MaterialTheme.shapes.small,
                color=if(filter==selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(4.dp)) {
                    AsyncImage(image,"${filter.label}效果",Modifier.fillMaxWidth().height(48.dp),contentScale=ContentScale.Crop)
                    Text(filter.label,style=MaterialTheme.typography.labelSmall,modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
