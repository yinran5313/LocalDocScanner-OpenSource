package com.localdoc.scanner.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.external.ExternalOpenBus
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.*
import java.io.File

/** The same save/share/reopen contract for results created during manual review. */
@Composable
internal fun ResultFileActions(file:File) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var status by remember(file) {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OutputHistoryStore.mimeFor(file))) {uri ->
        if(uri!=null) scope.launch {busy=true;try {
            status=withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri,"w")?.use {out ->file.inputStream().use {it.copyTo(out)}} ?: error("无法保存文件")
                runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                val label=OutputHistoryStore.describeDestination(context,uri);OutputHistoryStore.markSaved(context,file,uri,label);"已保存：$label"
            }
        } catch(e:Exception) {if(e is CancellationException) throw e;status="保存失败：${e.message}"} finally {busy=false} }
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        OutputFileDetails(file,status)
        SaveDefaultButton(listOf(file)) {status=it}
        AdaptiveActions(listOf(DockAction("另存为",{save.launch(file.name)},enabled=!busy),DockAction("分享",{Share.file(context,file,OutputHistoryStore.mimeFor(file))},style=ActionStyle.OUTLINED,enabled=!busy)))
        TextButton({ExternalOpenBus.offer(Intent(Intent.ACTION_VIEW).apply {setDataAndType(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file),OutputHistoryStore.mimeFor(file))},context.contentResolver)}) {Text("打开 / 继续处理")}
        if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
    }
}
