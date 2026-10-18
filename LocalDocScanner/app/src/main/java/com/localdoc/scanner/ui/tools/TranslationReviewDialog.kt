package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localdoc.scanner.data.*
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.models.*
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ui.components.*
import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
internal fun TranslationReviewDialog(request:ToolRequest,original:ReviewPage,direction:Boolean,token:String,onClose:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val prefix="translation-review:$token"
    var source by rememberToolState(request,"$prefix:source") {original.raw}
    var translation by rememberToolState(request,"$prefix:translation") {original.fields["译文"].orEmpty()}
    var output by rememberToolState<File?>(request,"$prefix:output") {null}
    var id by rememberToolState<String?>(request,"$prefix:task") {null}
    var applied by rememberToolState(request,"$prefix:applied") {""}
    var status by remember {mutableStateOf("")};var submitting by remember {mutableStateOf(false)}
    val tasks by remember {ToolTasks.watch(context)}.collectAsState(emptyList())
    val task=tasks.firstOrNull {it.id==id}
    val busy=submitting || task?.state in setOf(ToolTaskState.QUEUED,ToolTaskState.RUNNING)
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current;val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(task?.updatedAt) {if(task?.state==ToolTaskState.SUCCEEDED) {
        val stamp="${task.id}:${task.updatedAt}"
        if(applied!=stamp) {val result=withContext(Dispatchers.IO) {ToolTasks.outcome(context,task.id)}
            result?.reviewPages?.firstOrNull()?.fields?.get("译文")?.let {translation=it};output=null;applied=stamp
        }
    }}
    fun retranslate() {
        val pack=ModelCatalog.get(context,if(direction) "translate-zh-en" else "translate-en-zh")
        if(!ModelCatalog.installed(context,pack)) {status="请先在模型管理安装${pack.title}";return}
        if(source.isBlank() || source.length>8000) {status="请输入1至8000字符的原文";return}
        focusManager.clearFocus();keyboard?.hide();submitting=true
        scope.launch {try {id=ToolTasks.submit(context,request,mapOf("translationSource" to ToolDrafts.gson.toJson(source),"chineseToEnglish" to ToolDrafts.gson.toJson(direction)),action="translate_review")}
        catch(e:Exception) {if(e is CancellationException) throw e;status="提交失败：${e.message}"} finally {submitting=false}}
    }
    fun export() {
        focusManager.clearFocus();keyboard?.hide();submitting=true
        val text="原文（已校订）\n$source\n\n译文（人工复核）\n$translation"
        scope.launch {try {output=withContext(Dispatchers.IO) {
            val file=File(context.filesDir,"translation-reviews/${UUID.randomUUID()}/校订翻译.txt")
            AtomicFiles.text(file,text);OutputHistoryStore.recordGenerated(context,file,"text/plain");file
        }} catch(e:Exception) {if(e is CancellationException) throw e;status="保存副本失败：${e.message}"} finally {submitting=false}}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(topBar={ScannerTopBar("原文与译文复核",onClose)},bottomBar={if(output==null) ActionDock {
            AdaptiveActions(listOf(DockAction("重新翻译",::retranslate,enabled=!busy),DockAction("生成校订副本",::export,style=ActionStyle.OUTLINED,enabled=!busy && translation.isNotBlank())))
        }}) {padding ->
            val file=output?.takeIf(File::isFile)
            if(file!=null) Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                FilePreview(listOf(file),"校订翻译预览");ResultFileActions(file);TextButton({output=null}) {Text("继续复核")}
            } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("可先修正原文再翻译，也可直接校订译文。原结果保留。",style=MaterialTheme.typography.bodySmall)
                task?.let {ToolTaskPanel(it,{scope.launch {ToolTasks.pause(context,it.id)}},{scope.launch {runCatching {ToolTasks.resume(context,it.id)}.onFailure {error ->status=error.message.orEmpty()}}})}
                if(status.isNotBlank()) Text(status)
                ReviewSplit(Modifier.weight(1f),source={SourceReviewPane(original,null,it)},fields={pane ->
                    Column(pane.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(source,{source=it;output=null},label={Text("原文，可校订后重新翻译")},minLines=3,maxLines=12,enabled=!busy,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(translation,{translation=it;output=null},label={Text("译文，可人工校订")},minLines=3,maxLines=12,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    }
                })
            }
        }
    }
}
