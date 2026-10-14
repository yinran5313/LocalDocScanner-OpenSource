package com.localdoc.scanner.external

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

@Composable
internal fun ArchivePreview(file: File, modifier: Modifier = Modifier, onModelDetected: (Boolean) -> Unit = {}) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    var entries by remember(file) { mutableStateOf<List<Pair<String,Long>>?>(null) }
    var error by remember(file) { mutableStateOf("") }
    var pack by remember(file) { mutableStateOf<com.localdoc.scanner.models.ModelPack?>(null) }
    var installing by remember { mutableStateOf(false) }
    var status by remember(file) { mutableStateOf("") }
    LaunchedEffect(file,file.lastModified()) {
        withContext(Dispatchers.IO) { runCatching {
            ZipFile(file).use { zip ->
                val result=mutableListOf<Pair<String,Long>>()
                val input=zip.entries()
                while(input.hasMoreElements()) {
                    require(result.size<3000) { "文件超过3000项，请分包查看" }
                    val entry=input.nextElement(); result+=entry.name.take(1024) to entry.size
                }
                val model=runCatching {
                    val entry=zip.getEntry("model.json") ?: return@runCatching null
                    require(result.firstOrNull()?.first=="model.json" && entry.size in 1..8192)
                    val text=zip.getInputStream(entry).use { input ->
                        val buffer=ByteArray(8193); var bytes=0
                        while(bytes<buffer.size) { val n=input.read(buffer,bytes,buffer.size-bytes);if(n<0)break;bytes+=n }
                        require(bytes in 1..8192);String(buffer,0,bytes,Charsets.UTF_8)
                    }
                    val json=org.json.JSONObject(text)
                    com.localdoc.scanner.models.ModelCatalog.get(context,json.getString("id")).takeIf { it.version==json.getString("version") }
                }.getOrNull()
                result to model
            }
        } }.onSuccess { entries=it.first;pack=it.second;onModelDetected(pack!=null) }.onFailure { error="压缩包无法读取：${it.message}" }
    }
    LazyColumn(modifier,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("${entries?.size ?: 0} 个条目",style=MaterialTheme.typography.titleMedium)
            val model=pack
            if(model==null) Text("用「ZIP解压导入」提取文件，再分别预览或入库。",style=MaterialTheme.typography.bodySmall)
            else {
                Text("这是${model.title}离线包。安装时会核对文件清单和完整校验值，安装后可在对应工具中离线使用。",style=MaterialTheme.typography.bodySmall)
                Button(onClick={ scope.launch {
                    installing=true;status="正在核对和安装，请保持应用打开…"
                    try { com.localdoc.scanner.models.ModelDownloads.import(context,android.net.Uri.fromFile(file));status="${model.title}安装完成" }
                    catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;status="安装未完成：${e.message}" }
                    finally { installing=false }
                } },enabled=!installing,modifier=Modifier.fillMaxWidth()) { Text(if(installing) "正在安装…" else "安装离线模型包") }
                if(status.isNotBlank())Text(status)
            }
        }
        if(error.isNotBlank()) item { Text(error,color=MaterialTheme.colorScheme.error) }
        if(entries==null && error.isBlank())item { Text("正在读取文件清单…") }
        items(entries.orEmpty()) { (name,bytes) -> Column { Text(name); Text(if(bytes<0) "大小未知" else "${bytes} 字节",style=MaterialTheme.typography.bodySmall) } }
    }
}
