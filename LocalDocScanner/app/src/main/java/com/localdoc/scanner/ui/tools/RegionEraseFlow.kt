package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.pdf.PdfInkPoint
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.cv.RepairCandidate
import com.localdoc.scanner.cv.ObstructionDetector
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.*

@Composable
internal fun RegionEraseFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var learned by rememberToolState(request,"eraseLearned") { false }
    var models by remember { mutableStateOf(false) }
    if(models) { com.localdoc.scanner.models.ModelsScreen({ models=false }); return }
    var strokes by rememberToolState<List<List<PdfInkPoint>>>(request,"eraseStrokes") { emptyList() }
    var brush by rememberToolState(request,"eraseBrush") { 0.035f }
    var dimensions by remember { mutableStateOf(0 to 0) }
    var active by remember { mutableStateOf(emptyList<PdfInkPoint>()) }
    var candidates by rememberToolState<List<RepairCandidate>>(request,"repairCandidates") { emptyList() }
    var accepted by rememberToolState<List<Int>>(request,"repairAccepted") { emptyList() }
    var detecting by remember { mutableStateOf(false) }
    var detectStatus by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    ToolFlow(request,vm,onBack,onOpenDoc,modifier,runLabel = "修补选区并预览",config = {
        Text("单指涂抹杂点或小物体 → 调整笔刷 → 修补 → 对照结果。适合小范围背景；不能找回被遮挡的真实文字，原图不会覆盖。")
        OutlinedButton(onClick={ scope.launch {
            detecting=true
            try {
                candidates=withContext(Dispatchers.Default) {
                    val image=ImageIo.loadExactFromFile(request.files.first(),1024) ?: error("无法读取图片")
                    try { ObstructionDetector.detect(image) } finally { image.recycle() }
                }
                accepted=candidates.indices.toList(); detectStatus=if(candidates.isEmpty()) "未找到明确候选，可手动涂抹" else "找到${candidates.size}个候选，请逐个核对；点候选名称可取消选择"
            } catch(e: Exception) { if(e is CancellationException)throw e; detectStatus=e.message ?: "检测失败" }
            finally { detecting=false }
        } },enabled=!detecting) { Text(if(detecting) "正在检测…" else "检测反光 / 手指") }
        Text("检测使用局部亮度和边缘肤色规则，彩色印刷可能误判；橙框是待确认候选。遮住文字的区域建议重拍。",style=MaterialTheme.typography.bodySmall)
        if(detectStatus.isNotBlank()) Text(detectStatus,style=MaterialTheme.typography.bodySmall)
        if(candidates.isNotEmpty()) {
            com.localdoc.scanner.ui.components.WrappingOptions { candidates.forEachIndexed { i,c ->
                FilterChip(selected=i in accepted,onClick={ accepted=if(i in accepted)accepted-i else accepted+i },label={ Text("${i+1} ${c.kind}") })
            } }
            TextButton(onClick={
                val additions=accepted.flatMap { candidates[it].strokes }
                if(strokes.size+additions.size>1000 || strokes.sumOf { it.size }+additions.sumOf { it.size }>50_000) detectStatus="选区较多，请分次加入并修补"
                else { strokes=strokes+additions; brush=accepted.map { candidates[it].brush }.maxOrNull() ?: brush; candidates=emptyList(); accepted=emptyList(); detectStatus="已加入选区，可撤销或补充涂抹，再点修补" }
            },enabled=accepted.isNotEmpty()) { Text("采用已确认的候选选区") }
        }
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(learned,{ learned=it }); Text("使用背景修补模型",modifier=Modifier.weight(1f)) }
        Text(if(learned) "学习式修补适合选区周围有背景纹理的场景；大选区会缩到模型尺寸，细节需复核。" else "基础邻域修补不需要可选模型，适合小杂点。",style=MaterialTheme.typography.bodySmall)
        if(learned) {
            val pack=com.localdoc.scanner.models.ModelCatalog.get(context,"inpaint")
            Text(if(com.localdoc.scanner.models.ModelCatalog.installed(context,pack)) "模型已安装，处理可离线" else "先导入背景修补离线包（约199MiB）")
            TextButton({ models=true }) { Text("模型管理") }
        }
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            AsyncImage(request.files.firstOrNull(),"涂抹选区",Modifier.fillMaxSize(),contentScale = ContentScale.Fit,
                onSuccess = { dimensions = it.result.drawable.intrinsicWidth to it.result.drawable.intrinsicHeight })
            Canvas(Modifier.fillMaxSize().pointerInput(dimensions,brush) {
                val (w,h) = dimensions
                if (w <= 0 || h <= 0) return@pointerInput
                val scale = minOf(size.width.toFloat()/w,size.height.toFloat()/h)
                val ox = (size.width-w*scale)/2; val oy = (size.height-h*scale)/2
                fun normalized(p: Offset) = PdfInkPoint(((p.x-ox)/(w*scale)).coerceIn(0f,1f),((p.y-oy)/(h*scale)).coerceIn(0f,1f))
                detectDragGestures(onDragStart = { p -> active = if (p.x in ox..(ox+w*scale) && p.y in oy..(oy+h*scale)) listOf(normalized(p)) else emptyList() },
                    onDragEnd = { if (active.isNotEmpty() && strokes.sumOf { it.size }+active.size <= 50_000 && strokes.size < 1000) strokes = strokes+listOf(active); active = emptyList() },
                    onDragCancel = { active = emptyList() },
                    onDrag = { change,_ -> if (active.isNotEmpty() && active.size < 5000) { active = active+normalized(change.position); change.consume() } })
            }) {
                val (w,h) = dimensions
                if (w > 0 && h > 0) {
                    val scale = minOf(size.width/w,size.height/h); val ox = (size.width-w*scale)/2; val oy = (size.height-h*scale)/2
                    fun point(p: PdfInkPoint) = Offset(ox+p.x*w*scale,oy+p.y*h*scale)
                    candidates.forEachIndexed { i,c -> if(i in accepted) drawRect(Color(0x99FF9900),Offset(ox+c.left*w*scale,oy+c.top*h*scale),androidx.compose.ui.geometry.Size((c.right-c.left)*w*scale,(c.bottom-c.top)*h*scale),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
                    (strokes+listOf(active)).forEach { stroke ->
                        stroke.forEach { drawCircle(Color(0x77FF5733),brush*minOf(w,h)*scale/2,point(it)) }
                        stroke.zipWithNext().forEach { (a,b) -> drawLine(Color(0x77FF5733),point(a),point(b),brush*minOf(w,h)*scale,cap = StrokeCap.Round) }
                    }
                }
            }
        }
        Text("笔刷大小"); Slider(brush,{ brush = it },valueRange = 0.005f..0.15f)
        Row { TextButton({ strokes = strokes.dropLast(1) },enabled = strokes.isNotEmpty()) { Text("撤销上一笔") }; TextButton({ strokes = emptyList() }) { Text("清空选区") } }
    })
}
