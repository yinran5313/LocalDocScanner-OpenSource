package com.localdoc.scanner.ui.components
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.localdoc.scanner.data.rememberPreference
import com.localdoc.scanner.output.DefaultDestination
import kotlinx.coroutines.*
import java.io.File
@Composable
fun SaveDefaultButton(files:List<File>,onStatus:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val destination by rememberPreference("destination")
    var busy by remember { mutableStateOf(false) }
    if(destination.isNotBlank()) TextButton(onClick={ busy=true; scope.launch {
        try { val labels=withContext(Dispatchers.IO) { files.map { DefaultDestination.save(context,it) } }; onStatus("已保存：\n"+labels.joinToString("\n")) }
        catch(e:Exception) { if(e is CancellationException) throw e; onStatus("保存失败：${e.message}") }
        finally { busy=false }
    } },enabled=!busy && files.isNotEmpty()) { Text(if(busy) "保存中…" else "保存到默认文件夹") }
}
