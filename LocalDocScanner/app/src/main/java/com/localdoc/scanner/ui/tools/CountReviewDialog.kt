package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.reflect.TypeToken
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.data.ToolDrafts
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ui.components.*
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.min

@Composable
internal fun CountReviewDialog(request:ToolRequest,detail:File,label:String,onClose:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val key="count-review:${detail.absolutePath}"
    var marks by rememberToolState<List<CountMark>?>(request,key) {null}
    var results by rememberToolState<List<File>>(request,"$key:files") {emptyList()}
    var undo by remember {mutableStateOf(emptyList<List<CountMark>>())};var redo by remember {mutableStateOf(emptyList<List<CountMark>>())}
    var mode by remember {mutableIntStateOf(0)};var busy by remember {mutableStateOf(false)};var status by remember {mutableStateOf("")}
    val bitmap by rememberDisplayBitmap(request.files.first()) {ImageIo.loadFromFile(request.files.first(),2200)}
    LaunchedEffect(detail) {if(marks==null) try {
        marks=withContext(Dispatchers.IO) {CountReview.detected(ToolDrafts.gson.fromJson(detail.readText(),object:TypeToken<List<CountedObject>>(){}.type))}
    } catch(e:Exception) {if(e is CancellationException) throw e;status="读取检测结果失败：${e.message}"}}
    fun replace(value:List<CountMark>) {val current=marks.orEmpty();if(value!=current) {undo=(undo+listOf(current)).takeLast(30);redo=emptyList();marks=value;results=emptyList()}}
    val currentMarks by rememberUpdatedState(marks.orEmpty());val currentMode by rememberUpdatedState(mode);val blocked by rememberUpdatedState(busy)
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(topBar={ScannerTopBar("计数复核",onClose)},bottomBar={if(results.isEmpty()) ActionDock {
            AdaptiveActions(listOf(DockAction("确认并生成结果",{
                val image=bitmap ?: return@DockAction;val confirmed=marks.orEmpty();busy=true
                scope.launch {try {results=withContext(Dispatchers.IO) {CountReview.export(context,image,confirmed,label,File(context.filesDir,"count-reviews/${UUID.randomUUID()}"))}}
                catch(e:Exception) {if(e is CancellationException) throw e;status="生成失败：${e.message}"} finally {busy=false}}
            },enabled=bitmap!=null && marks!=null && !busy)))
        }}) {padding ->
            if(results.isNotEmpty()) Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("人工复核结果 · $label · 数量：${marks.orEmpty().size}",style=MaterialTheme.typography.titleMedium)
                FilePreview(listOf(results.first()),"标记图预览")
                Text("ZIP包含标记图、复核数量CSV和位置JSON。",style=MaterialTheme.typography.bodySmall)
                ResultFileActions(results.last())
                TextButton({results=emptyList()}) {Text("继续在图上修改")}
            } else Column(Modifier.fillMaxSize().padding(padding).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("数量：${marks.orEmpty().size}",style=MaterialTheme.typography.titleLarge)
                ChipRow(listOf("补点","删除","查看 / 缩放"),mode) {mode=it}
                Text("点图补漏或删除附近标记；双指缩放，双击恢复。绿色为检测建议，橙色为人工补点。",style=MaterialTheme.typography.bodySmall)
                val image=bitmap
                if(image!=null) ZoomableImage(image,request.files.first(),Modifier.fillMaxWidth().weight(1f).testTag("count-review-canvas")) {
                    Canvas(Modifier.fillMaxSize().pointerInput(image) {
                        detectTapGestures {point ->
                            if(blocked || currentMode==2) return@detectTapGestures
                            val scale=min(size.width.toFloat()/image.width,size.height.toFloat()/image.height)
                            val width=image.width*scale;val height=image.height*scale
                            val left=(size.width-width)/2;val top=(size.height-height)/2
                            val x=(point.x-left)/width;val y=(point.y-top)/height
                            if(x in 0f..1f && y in 0f..1f) replace(CountReview.changed(currentMarks,x,y,currentMode==1,24.dp.toPx()/width,width/height))
                        }
                    }) {
                        val scale=min(size.width/image.width,size.height/image.height)
                        val width=image.width*scale;val height=image.height*scale;val left=(size.width-width)/2;val top=(size.height-height)/2
                        val radius=10.dp.toPx();val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textAlign=android.graphics.Paint.Align.CENTER;textSize=radius*1.1f;color=android.graphics.Color.WHITE}
                        marks.orEmpty().forEachIndexed {index,p ->
                            val point=Offset(left+p.x*width,top+p.y*height)
                            drawCircle(if(p.manual) Color(0xFFE6711A) else Color(0xFF00896A),radius,point)
                            drawIntoCanvas {it.nativeCanvas.drawText("${index+1}",point.x,point.y+radius*.4f,paint)}
                        }
                    }
                } else Text("正在读取图片…")
                WrappingOptions {
                    TextButton({val previous=undo.lastOrNull() ?: return@TextButton;redo=(redo+listOf(marks.orEmpty())).takeLast(30);undo=undo.dropLast(1);marks=previous;results=emptyList()},enabled=undo.isNotEmpty() && !busy) {Text("撤销")}
                    TextButton({val next=redo.lastOrNull() ?: return@TextButton;undo=(undo+listOf(marks.orEmpty())).takeLast(30);redo=redo.dropLast(1);marks=next;results=emptyList()},enabled=redo.isNotEmpty() && !busy) {Text("重做")}
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(status.isNotBlank()) Text(status)
            }
        }
    }
}
