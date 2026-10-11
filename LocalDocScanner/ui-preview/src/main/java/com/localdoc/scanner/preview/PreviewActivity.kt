package com.localdoc.scanner.preview

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.R
import com.localdoc.scanner.model.DocItem
import com.localdoc.scanner.ui.components.*
import com.localdoc.scanner.ui.home.HomeScreen
import com.localdoc.scanner.ui.theme.LocalDocScannerTheme
import java.io.File

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val cover = File(filesDir, "document.jpg")
        assets.open("searchable-pdf-page.jpg").use { source -> cover.outputStream().use { source.copyTo(it) } }
        val demo = listOf("年度工作资料", "费用报销凭证", "合同与附件", "学习笔记与知识点整理").mapIndexed { index, title -> DocItem("demo$index", title, index + 1, System.currentTimeMillis(), 420000, cover.absolutePath, if (index == 1) "财务" else null, if (index == 1) "待复核" else "") }
        val screen = intent.getStringExtra("screen") ?: "home"
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        val pdf = File(filesDir,"reader-fixture.pdf")
        if(!pdf.isFile) com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            repeat(6) { index ->
                val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page).use { stream ->
                    stream.beginText(); stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,24f); stream.newLineAtOffset(42f,740f); stream.showText("SHIYE / PAGE ${index+1}"); stream.endText()
                    repeat(22) { line -> stream.beginText(); stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12f); stream.newLineAtOffset(42f,690f-line*26f); stream.showText("Continuous reading - document line ${line+1}"); stream.endText() }
                }
            }
            doc.save(pdf)
        }
        fun action() { Toast.makeText(this, "界面预览，不执行业务操作", Toast.LENGTH_SHORT).show() }
        setContent { LocalDocScannerTheme(darkTheme = intent.getBooleanExtra("dark", false)) {
            AppSafeFrame {
            when (screen) {
                "pdf" -> Scaffold(topBar={ ScannerTopBar("拾页 · 阅读测试",{ finish() },"实际PDF · 拖动 / 缩放") }) { padding ->
                    com.localdoc.scanner.ui.tools.PdfReader(pdf,Modifier.fillMaxSize().padding(padding))
                }
                "brand" -> Scaffold(topBar={ ScannerTopBar("拾页图标",{ finish() },"Android实际自适应图标") }) { padding -> Column(Modifier.padding(padding).padding(24.dp)) {
                    AndroidView(factory={ context -> android.widget.ImageView(context).apply { setImageDrawable(context.getDrawable(R.mipmap.ic_launcher)) } },modifier=Modifier.size(144.dp))
                    Text("拾页",style=MaterialTheme.typography.headlineMedium)
                } }
                "tasks" -> Scaffold(topBar = { ScannerTopBar("工具任务", { finish() }, "进度与已完成结果") }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("文字识别", style = MaterialTheme.typography.titleMedium)
                        TaskProgressCard("已暂停", "已识别3页，已完成的结果会保留。", 3, 8, false, true, onResume = ::action)
                        Text("PDF合并", style = MaterialTheme.typography.titleMedium)
                        TaskProgressCard("已完成", "合并文件已生成，可打开预览并保存。", 1, 1, false, false)
                    }
                }
                "result" -> Scaffold(topBar = { ScannerTopBar("PDF编辑", { finish() }, "处理结果") }, bottomBar = { ActionDock {
                    Button(onClick = ::action, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text("保存到手机") }
                    OutlinedButton(onClick = ::action, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text("分享") }
                } }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TaskProgressCard("已完成", "文件已生成，保存后可从导出记录重新打开。", 1, 1, false, false)
                    SectionHeading("真实结果预览")
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) { AsyncImage(cover, null, modifier = Modifier.fillMaxWidth().height(300.dp).padding(16.dp)) }
                    InfoCard("工作资料_编辑.pdf", "结果保存在应用内；点击下方按钮选择保存位置。", R.drawable.ic_tool_picture_as_pdf)
                } }
                else -> HomeScreen(if (intent.getBooleanExtra("empty", false)) emptyList() else demo,
                    if (intent.getBooleanExtra("draft", false)) 3 else 0, "", ::action, ::action, ::action, ::action, ::action, ::action, ::action, ::action, ::action, ::action, ::action, ::action,
                    onToolClick = { action() }, onDocClick = { action() }, onRenameDoc = { _, _ -> action() }, onOrganizeDoc = { _, _, _ -> action() }, onTrashDoc = { action() })
            }
            }
        } }
    }
}
