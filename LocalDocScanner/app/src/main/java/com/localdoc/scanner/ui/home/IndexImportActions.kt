package com.localdoc.scanner.ui.home
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.localdoc.scanner.data.*
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun IndexImportActions(context: Context, onStatus: (String) -> Unit) {
    val scope=rememberCoroutineScope()
    var passwordFile by remember { mutableStateOf<File?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) scope.launch {
            busy=true
            try {
                val failures=withContext(Dispatchers.IO) { uris.mapNotNull { uri ->
                    runCatching {
                        val name=com.localdoc.scanner.output.OutputHistoryStore.displayName(context,uri)
                        val target=File(FileStore.importDir(context), "index_${java.util.UUID.randomUUID()}_${name.replace(Regex("[\\\\/:*?\"<>|]"),"_")}")
                        try { context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("无法读取") }
                        catch(e:Exception) { target.delete(); throw e }
                        com.localdoc.scanner.output.OutputHistoryStore.recordGenerated(context,target,com.localdoc.scanner.output.OutputHistoryStore.mimeFor(target))
                        try { FullTextIndex(context).use { it.indexFile(target,name) } }
                        catch(e:com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { withContext(Dispatchers.Main) { passwordFile=target }; throw IllegalStateException("$name 需要密码") }
                    }.exceptionOrNull()?.message
                } }
                onStatus("已处理${uris.size}个导入文件，${failures.size}个需要处理。\n"+failures.joinToString("\n"))
            } finally { busy=false }
        }
    }
    Button(onClick={ picker.launch(arrayOf("application/pdf","application/msword","application/vnd.ms-excel","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation","text/*")) },enabled=!busy) { Text("添加文件到全文索引") }
    passwordFile?.let { file -> AlertDialog(onDismissRequest={ passwordFile=null; password="" }, title={ Text("PDF索引需要密码") }, text={ Column {
        Text("提取的文字将保存到本地索引，供搜索使用；原PDF仍加密，密码不会保存。")
        OutlinedTextField(password,{ password=it },label={ Text("PDF密码") },visualTransformation=PasswordVisualTransformation(),singleLine=true)
    } },confirmButton={ TextButton(onClick={
        val secret=password; password=""; busy=true
        scope.launch { try {
            withContext(Dispatchers.IO) { FullTextIndex(context).use { it.indexFile(file,file.name,password=secret) } }
            passwordFile=null; onStatus("密码PDF已加入索引；密码未保存")
        } catch(e:Exception) { if(e is CancellationException) throw e; onStatus("索引失败：${e.message}") }
        finally { busy=false } }
    },enabled=!busy) { Text("解锁并建立索引") } },dismissButton={ TextButton(onClick={ passwordFile=null; password="" }) { Text("取消") } }) }
}
