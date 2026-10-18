package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ui.components.*
import com.localdoc.scanner.util.AtomicFiles
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
internal fun OcrReviewDialog(request:ToolRequest,job:String,keys:List<String>,onClose:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current;val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var edits by rememberToolState<Map<String,List<String>>>(request,"ocr-review:$job:edits") {emptyMap()}
    var confirmed by rememberToolState<List<String>>(request,"ocr-review:$job:confirmed") {emptyList()}
    var output by rememberToolState<File?>(request,"ocr-review:$job:output") {null}
    var selected by rememberToolState(request,"ocr-review:$job:selected") {0}
    var page by remember(job) {mutableStateOf<ReviewPage?>(null)};var focus by remember {mutableStateOf<SourceFocus?>(null)}
    var error by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    val key=keys.getOrNull(selected.coerceIn(0,keys.lastIndex.coerceAtLeast(0)))
    LaunchedEffect(job,key) {page=null;focus=null;error="";if(key!=null) try {page=withContext(Dispatchers.IO) {OcrReviewStore.load(context,job,key)}}
        catch(e:Exception) {if(e is CancellationException) throw e;error=e.message.orEmpty()}}
    fun export() {if(busy) return;focusManager.clearFocus();keyboard?.hide();busy=true;val corrections=edits;val reviewed=confirmed
        scope.launch {try {
            output=withContext(Dispatchers.IO) {
                val target=File(context.filesDir,"ocr-reviews/${UUID.randomUUID()}/校订文字.txt")
                val text=keys.joinToString("\n\n") {ref ->
                    val source=OcrReviewStore.load(context,job,ref)
                    val name=request.names.getOrNull(request.files.indexOf(File(source.source))) ?: "原文件"
                    "【$name · 第${source.page+1}页 · ${if(ref in reviewed) "已确认" else "待复核"}】\n"+(corrections[ref] ?: source.raw.lines()).joinToString("\n")
                }
                AtomicFiles.text(target,text);OutputHistoryStore.recordGenerated(context,target,"text/plain");target
            }
        } catch(e:Exception) {if(e is CancellationException) throw e;error="校订副本生成失败：${e.message}"} finally {busy=false}}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(topBar={ScannerTopBar("原图对照校订",onClose)},bottomBar={if(output==null) ActionDock {
            AdaptiveActions(listOf(DockAction("确认本页",{
                focusManager.clearFocus();keyboard?.hide()
                if(key!=null) {confirmed=(confirmed+key).distinct();val next=keys.indices.firstOrNull {it>selected && keys[it] !in confirmed};if(next!=null) selected=next}
            },enabled=page!=null && !busy),DockAction("生成文字副本",::export,style=ActionStyle.OUTLINED,enabled=keys.isNotEmpty() && !busy)))
        }}) {padding ->
            val ready=output?.takeIf(File::isFile)
            if(ready!=null) Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                FilePreview(listOf(ready),"校订文字副本")
                Text("已保存原有识别结果。此副本包含校订文字和逐页复核状态；已有PDF的文字层不变。",style=MaterialTheme.typography.bodySmall)
                ResultFileActions(ready)
                TextButton({output=null}) {Text("继续校订")}
            } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton({selected=(selected-1).coerceAtLeast(0)},enabled=selected>0 && !busy) {Text("上一页")}
                    Text("${selected+1}/${keys.size} · 已确认${confirmed.size}页",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                    TextButton({selected=(selected+1).coerceAtMost(keys.lastIndex)},enabled=selected<keys.lastIndex && !busy) {Text("下一页")}
                }
                Text("点一行文字可对照原图；拖动分隔条调整空间。修改后需重新确认。",style=MaterialTheme.typography.bodySmall)
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val current=page
                if(current!=null && key!=null) ReviewSplit(Modifier.weight(1f),source={SourceReviewPane(current,focus,it)},fields={pane ->
                    val original=current.raw.lines();val lines=edits[key] ?: original
                    LazyColumn(pane.imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        item {Text(if(key in confirmed) "此页已确认" else "此页待复核",color=MaterialTheme.colorScheme.primary)}
                        itemsIndexed(lines) {index,line ->
                            OutlinedTextField(line,{value ->edits=edits+(key to lines.mapIndexed {i,old ->if(i==index) value else old});confirmed=confirmed-key;output=null},label={Text("第${index+1}行")},enabled=!busy,maxLines=5,
                                modifier=Modifier.fillMaxWidth().onFocusChanged {if(it.isFocused) focus=SourceFocus("第${index+1}行",original.getOrElse(index) {line})})
                        }
                    }
                }) else if(error.isBlank()) Text("正在读取已完成的medium识别回执…")
            }
        }
    }
}
