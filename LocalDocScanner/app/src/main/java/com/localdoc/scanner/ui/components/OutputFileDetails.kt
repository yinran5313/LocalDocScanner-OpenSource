package com.localdoc.scanner.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.output.OutputRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun OutputFileDetails(file:File,refreshKey:Any?=null,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    var record by remember(file) { mutableStateOf<OutputRecord?>(null) }
    LaunchedEffect(file,refreshKey) {record=withContext(Dispatchers.IO) {OutputHistoryStore.all(context).firstOrNull {it.internalPath==file.absolutePath}}}
    val bytes=file.length()
    val size=when {bytes<1024 -> "$bytes B";bytes<1024*1024 -> "%.1f KB".format(bytes/1024.0);else -> "%.1f MB".format(bytes/(1024.0*1024))}
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(file.name,style=MaterialTheme.typography.titleSmall)
        Text("${file.extension.uppercase()} · $size",style=MaterialTheme.typography.bodySmall)
        Text(if(record?.savedUri?.isNotBlank()==true) "已另存：${record!!.savedLabel}" else "保存位置：应用内 · 可从首页导出记录重开",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(record?.name?.let {it!=file.name}==true) Text("保存名：${record!!.name}",style=MaterialTheme.typography.bodySmall)
    }
}
