package com.localdoc.scanner.ui.tools

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.localdoc.scanner.data.*
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.models.*
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import com.localdoc.scanner.ui.components.ScannerTopBar
import com.localdoc.scanner.util.AtomicFiles
import com.localdoc.scanner.util.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

@Composable
internal fun FormulaFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var openModels by remember { mutableStateOf(false) }; var submitting by remember { mutableStateOf(false) }
    var id by remember(request) { mutableStateOf(ToolTasks.latest(context,request)) }
    val tasks by remember { ToolTasks.watch(context) }.collectAsState(emptyList()); val task = tasks.firstOrNull { it.id == id }
    var latex by rememberToolState(request,"formulaLatex") { "" }; var applied by rememberToolState(request,"formulaApplied") { "" }
    var files by rememberToolState<List<File>>(request,"formulaExports") { emptyList() }; var status by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }; var pending by remember { mutableStateOf<File?>(null) }
    var previewReady by remember { mutableStateOf(false) }
    val busy = submitting || task?.state in setOf(ToolTaskState.QUEUED,ToolTaskState.RUNNING)
    LaunchedEffect(task?.updatedAt) {
        if (task?.state == ToolTaskState.SUCCEEDED) {
            val token = "${task!!.id}:${task.updatedAt}"
            if (applied != token) { latex = withContext(Dispatchers.IO) { ToolTasks.outcome(context,task.id)?.copyText.orEmpty() }; files = emptyList(); applied = token }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pending
        if (uri != null && file != null) scope.launch { status = withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.openOutputStream(uri,"w")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法创建文件")
            val label = OutputHistoryStore.describeDestination(context,uri); OutputHistoryStore.markSaved(context,file,uri,label); "已保存：$label"
        }.getOrElse { "保存失败：${it.message}" } } }
    }
    if (openModels) { ModelsScreen({ openModels = false }); return }
    Scaffold(topBar = { ScannerTopBar("公式识别",onBack,"单条公式 · 本地识别与校订") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先裁成一条公式 → 导入模型包 → 识别 → 修改LaTeX并检查预览 → 导出或打印。整页、复杂手写与低清笔迹需要人工校订；不用普通文字OCR冒充公式模型。")
            TextButton({ openModels = true }) { Text("模型管理 / 导入公式离线包") }
            FilePreview(request.files,"公式原图")
            Button({ submitting = true; scope.launch { try { id = ToolTasks.submit(context,request,emptyMap()) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() } finally { submitting = false } } },enabled = !busy && ModelCatalog.installed(context,ModelCatalog.get(context,"formula"))) { Text(if (busy) "正在识别…" else "识别公式") }
            task?.let { current -> ToolTaskPanel(current,{ scope.launch { ToolTasks.pause(context,current.id) } },{ scope.launch { try { ToolTasks.resume(context,current.id) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() } } }) }
            OutlinedTextField(latex,{ latex = it.take(32_000); files = emptyList(); previewReady = false },label = { Text("LaTeX（也可手工输入）") },minLines = 3,maxLines = 8,modifier = Modifier.fillMaxWidth())
            if (latex.isNotBlank()) {
                Text("公式预览",style = MaterialTheme.typography.titleSmall)
                FormulaPreview(latex,{ webView = it; previewReady = true })
                com.localdoc.scanner.ui.components.WrappingOptions {
                    Button({ submitting = true; scope.launch { try {
                        val content = latex
                        val generated = withContext(Dispatchers.IO) {
                            val prefix = "公式_${java.util.UUID.randomUUID()}"
                            listOf(File(FileStore.exportDir(context),"$prefix.tex").also { AtomicFiles.text(it,"\\documentclass{article}\n\\usepackage{amsmath}\n\\begin{document}\n\\[\n$content\n\\]\n\\end{document}\n") },
                                File(FileStore.exportDir(context),"$prefix.md").also { AtomicFiles.text(it,"$$\n$content\n$$\n") }).also { list -> list.forEach { OutputHistoryStore.recordGenerated(context,it,OutputHistoryStore.mimeFor(it)) } }
                        }
                        if (latex == content) files = generated
                        status = "已生成校订后的结果，请保存或分享"
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; status = e.message.orEmpty() } finally { submitting = false } } },enabled = !submitting) { Text("生成校订后的文件") }
                    TextButton({ webView?.let { view -> (context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager).print("公式",view.createPrintDocumentAdapter("公式"),android.print.PrintAttributes.Builder().build()) } },enabled = previewReady) { Text("打印 / 保存PDF") }
                }
            }
            Text(status)
            files.forEach { file -> Row {
                TextButton({ pending = file; save.launch(file.name) }) { Text("保存 ${file.extension.uppercase()}") }
                TextButton({ Share.file(context,file,OutputHistoryStore.mimeFor(file)) }) { Text("分享") }
            } }
            if (files.isNotEmpty()) com.localdoc.scanner.ui.components.SaveDefaultButton(files) { status = it }
        }
    }
}

@Composable
internal fun FormulaPreview(latex: String, ready: (WebView) -> Unit) {
    val encoded = JSONObject.quote(latex).replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")
    val html = """<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="katex.min.css"><script src="katex.min.js"></script><style>body{padding:20px;background:white;color:black}#formula{overflow:auto} @page{size:A4;margin:20mm}</style></head><body><div id="formula"></div><script>try{katex.render($encoded,document.getElementById('formula'),{displayMode:true,throwOnError:true,trust:false,maxExpand:100,maxSize:20});}catch(e){document.getElementById('formula').textContent='公式语法需要校正：'+e.message;}</script></body></html>"""
    AndroidView(factory = { context -> WebView(context).apply {
        settings.javaScriptEnabled = true; settings.blockNetworkLoads = true; settings.allowFileAccess = false; settings.allowContentAccess = false
        webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true; override fun onPageFinished(view: WebView?, url: String?) { ready(this@apply) } }
    } },update = { view -> if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("file:///android_asset/katex/",html,"text/html","UTF-8",null) } },onRelease = { it.stopLoading(); it.destroy() },modifier = Modifier.fillMaxWidth().height(220.dp))
}
