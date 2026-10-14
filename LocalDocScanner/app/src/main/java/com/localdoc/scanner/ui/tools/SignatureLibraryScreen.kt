package com.localdoc.scanner.ui.tools

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.pdf.*
import com.localdoc.scanner.ui.components.ScannerTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SignatureLibraryScreen(onBack: () -> Unit, onSelect: ((Uri) -> Unit)? = null) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var signatures by remember { mutableStateOf(SignatureStore.all(context)) }
    var strokes by remember { mutableStateOf(emptyList<List<PdfInkPoint>>()) }
    var name by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(2.4f) }; var removing by remember { mutableStateOf<SavedSignature?>(null) }
    Scaffold(topBar = { ScannerTopBar("签名库", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("手写一次，保存为透明PNG。以后在PDF的图片签名入口复用；加入编辑清单前可移动、缩放，清单中可移除。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("签名名称") }, modifier = Modifier.fillMaxWidth())
                SignaturePad(strokes, { strokes = it }, onAspect = { aspect = it })
                Row {
                    Button({ busy = true; scope.launch {
                        try { withContext(Dispatchers.IO) { SignatureStore.save(context, name, strokes, aspect) }; signatures = SignatureStore.all(context); strokes = emptyList(); status = "签名已保存在本机" }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() }
                        finally { busy = false }
                    } }, enabled = !busy && strokes.any { it.size >= 2 }) { Text("保存签名") }
                    TextButton({ strokes = emptyList() }) { Text("清除重写") }
                }
                Text(status)
            }
            items(signatures, key = { it.id }) { signature ->
                Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(signature.name)
                    AsyncImage(signature.file, signature.name, Modifier.fillMaxWidth().height(100.dp).background(androidx.compose.ui.graphics.Color.White))
                    Row {
                        if (onSelect != null) TextButton({ onSelect(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", signature.file)) }) { Text("使用此签名") }
                        else TextButton({ com.localdoc.scanner.util.Share.file(context, signature.file, "image/png") }) { Text("分享PNG") }
                        TextButton({ removing = signature }) { Text("删除") }
                    }
                } }
            }
        }
    }
    removing?.let { signature -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除${signature.name}？") },
        text = { Text("已生成的签署文件保留。") }, confirmButton = { TextButton({
            runCatching { SignatureStore.remove(context, signature) }.onSuccess { signatures = SignatureStore.all(context) }.onFailure { status = it.message.orEmpty() }; removing = null
        }) { Text("删除") } }, dismissButton = { TextButton({ removing = null }) { Text("取消") } }) }
}
