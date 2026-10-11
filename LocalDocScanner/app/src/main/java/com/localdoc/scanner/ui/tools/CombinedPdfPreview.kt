package com.localdoc.scanner.ui.tools
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.PendingPdfEdit
import com.localdoc.scanner.pdf.PdfEditPipeline
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun CombinedPdfPreview(source:File, edits:List<PendingPdfEdit>, page:Int) {
    val context=LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val key=com.localdoc.scanner.data.ToolDrafts.gson.toJson(edits)
    LaunchedEffect(source,key,expanded) {
        if(!expanded || edits.isEmpty()) return@LaunchedEffect
        busy=true; error=""; file=null
        delay(250)
        var pending:File?=null
        try {
            val target=withContext(Dispatchers.IO) {
                val dir=File(context.cacheDir,"pdf-combined-${java.util.UUID.randomUUID()}").also { it.mkdirs(); pending=it }
                var input=source
                edits.forEachIndexed { index, edit ->
                    ensureActive()
                    val next=File(dir,"step_$index.pdf")
                    check(PdfEditPipeline.apply(context,input,next,edit)) { "${edit.label}不能应用，请调整或移除此操作" }
                    if(input != source) input.delete()
                    input=next
                }
                input
            }
            file=target; pending=null
        } catch(e:Exception) { if(e is CancellationException) throw e; error="组合预览失败：${e.message}" }
        finally { pending?.deleteRecursively(); busy=false }
    }
    val current=file
    DisposableEffect(current) { onDispose { current?.parentFile?.deleteRecursively() } }
    OutlinedButton(onClick={ expanded=!expanded },enabled=edits.isNotEmpty()) { Text(if(expanded) "收起组合预览" else "查看 ${edits.size} 项编辑的实际效果") }
    if(expanded) {
        Text("以下是同一导出处理链生成的临时PDF，原件不变；移除待提交操作后会重新生成。",style=MaterialTheme.typography.bodySmall)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        current?.let { PdfReader(it,Modifier.fillMaxWidth().height(520.dp),initialPage=page) }
    }
}
