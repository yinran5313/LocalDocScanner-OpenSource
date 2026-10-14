package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.localdoc.scanner.data.rememberToolState
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest

@Composable
internal fun AdvancedToolsFlow(request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var background by rememberToolState(request, "background") { 0 }
    var mdColumns by rememberToolState(request,"mdColumns") { "AUTO" }
    var mdTable by rememberToolState(request,"mdTable") { false }
    var mdLayout by rememberToolState(request,"mdLayout") { false }
    var mdSourceImages by rememberToolState(request,"mdSourceImages") { true }
    var splitSpread by rememberToolState(request,"splitSpread") { false }
    var splitRatio by rememberToolState(request,"splitRatio") { 0.5f }
    var pdfSuperResolution by rememberToolState(request,"pdfSuperResolution") { false }
    var pdfEnhanceSearchable by rememberToolState(request,"pdfEnhanceSearchable") { false }
    var pdfEnhanceSide by rememberToolState(request,"pdfEnhanceSide") { 1200 }
    var restoreScale by rememberToolState(request, "restoreScale") { 2 }
    var restoreMode by rememberToolState(request,"restoreMode") { "SUPER" }
    var restoreStrength by rememberToolState(request,"restoreStrength") { .7f }
    var photoSize by rememberToolState(request, "photoSize") { 0 }
    var cropX by rememberToolState(request, "photoCropX") { 0.5f }
    var cropY by rememberToolState(request, "photoCropY") { 0.5f }
    var category by rememberToolState(request, "category") { 0 }
    var chineseToEnglish by rememberToolState(request, "chineseToEnglish") { true }
    var pages by rememberToolState(request, "selectedPages") { "" }
    var qrText by rememberToolState(request, "qrText") { "" }
    var watermarkText by rememberToolState(request, "watermarkText") { "" }
    var watermarkLocation by rememberToolState(request, "watermarkLocation") { "" }
    var watermarkTime by rememberToolState(request, "watermarkTime") { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(request.files.firstOrNull()?.lastModified()?.takeIf { it > 0 } ?: System.currentTimeMillis())) }
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var locationStatus by remember { mutableStateOf("") }
    fun locate() { scope.launch {
        locating = true
        try { com.localdoc.scanner.capture.LocalLocation.coordinates(context)?.let { watermarkLocation = it; locationStatus = "已取得设备位置" } ?: run { locationStatus = "暂未取得位置，可手动填写或留空" } }
        finally { locating = false }
    } }
    val locationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) locate() else locationStatus = "位置可留空，也可以手动填写"
    }
    var openModels by remember { mutableStateOf(false) }
    if (openModels) { ModelsScreen(onBack = { openModels = false }); return }
    val modelId = when (request.tool.id) { "photo_restore" -> when(restoreMode) { "DEBLUR" -> "deblur"; "DENOISE" -> null; else -> "restore" }; "pdf_enhance" -> if(pdfSuperResolution) "restore" else null; "portrait" -> "portrait"; "book_unwarp" -> "unwarp"; "structured_markdown" -> if(mdLayout) "layout" else if (mdTable) "table" else null; "translate" -> if (chineseToEnglish) "translate-zh-en" else "translate-en-zh"; "count_objects" -> "objects"; else -> null }
    ToolFlow(request, vm, onBack, onOpenDoc, modifier, config = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when(request.tool.id) {
                "photo_restore" -> "选择高清、去模糊或降噪 → 对照原图 → 保存副本。高清和去模糊逐图块续跑，降噪无需模型。无法保证恢复丢失的真实文字。"
                "portrait" -> "选择一张人物照片 → 选择背景 → 本地抠图 → 对照原图复核 → 保存PNG。透明背景可用于其它编辑器。"
                "book_unwarp" -> "选择书页照片 → 本地曲面展平 → 对照原图复核 → 保存副本。文字被遮挡的内容无法恢复。"
                "translate" -> "图片文字先用内置 medium 识别，再使用所选方向的本地翻译模型。译文可编辑复核并导出。"
                "structured_markdown" -> "识别文字 → 可选版面模型区分标题、正文、表格、插图与公式 → 按栏整理阅读顺序 → 预览并保存完整ZIP。插图和公式保留区域原图，文字可编辑。"
                "pdf_long" -> "输入页码 → 渲染选页 → 合成长图。超过分块高度会分成多张，避免大文档耗尽内存。"
                "pdf_enhance" -> "选页 → 按页增强 → 预览 → 保存副本。保持原可见页面尺寸，可选高清模型与新的medium文字层；原表单和批注不迁移，请保留原PDF。"
                "zip_import" -> "解压ZIP到私有结果区 → 检查各文件 → 保存、分享或导入文档库。不会自动执行压缩包内容。"
                "watermark_photo" -> "设置时间、地点和自定义文字 → 预览水印照片 → 保存或分享副本。地点可留空或手动填写，仅在你点按钮时请求设备位置。"
                "count_objects" -> "选择计数类别 → 本地识别 → 对照编号框检查遗漏与重复。仅支持列表中的类别，数量是识别建议；点开CSV可以记录人工复核数量。"
                else -> "输入文字或链接 → 生成二维码 → 保存PNG或分享。链接是否打开由接收方选择。"
            }, style = MaterialTheme.typography.bodySmall)
            if (modelId != null) {
                val pack = ModelCatalog.get(context, modelId)
                Text(if (ModelCatalog.installed(context, pack)) "${pack.title}已安装，处理不需要联网" else "先下载或导入${pack.title}（${"%.1f".format(pack.bytes / 1048576.0)} MiB）")
                TextButton({ openModels = true }) { Text("打开模型管理") }
            }
            when(request.tool.id) {
                "book_unwarp" -> {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(splitSpread,{ splitSpread = it }); Text("原图是左右双页，先拆分再展平",modifier=Modifier.weight(1f)) }
                    if (splitSpread) { Text("书缝位置 ${"%.0f".format(splitRatio*100)}%"); Slider(splitRatio,{ splitRatio = it },valueRange = 0.3f..0.7f) }
                    Text("连续书籍扫描已拆页时保持关闭。也可在文档导出菜单对整本书页批量展平。",style = MaterialTheme.typography.bodySmall)
                }
                "structured_markdown" -> {
                    ChipRow(listOf("自动分栏","单栏","双栏"),listOf("AUTO","SINGLE","TWO").indexOf(mdColumns).coerceAtLeast(0)) { mdColumns = listOf("AUTO","SINGLE","TWO")[it] }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(mdLayout,{ mdLayout=it }); Text("识别图文版面与独立区域",modifier=Modifier.weight(1f)) }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(mdTable,{ mdTable = it }); Text(if(mdLayout) "恢复各表格区域的行列" else "页面主要是表格，使用结构模型",modifier=Modifier.weight(1f)) }
                    if(mdLayout && mdTable) {
                        val tablePack=ModelCatalog.get(context,"table")
                        Text(if(ModelCatalog.installed(context,tablePack)) "表格模型已安装" else "行列恢复还需安装表格模型；失败时保留区域原图与文字",style=MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(mdSourceImages,{ mdSourceImages=it }); Text("附原页图像与分享包",modifier=Modifier.weight(1f)) }
                    Text("原图作为逐页对照附件，不会假装恢复复杂图表。分享ZIP并整体解压，保留Markdown内的相对图片引用。",style=MaterialTheme.typography.bodySmall)
                    Text("表格模式需可选模型，合并格位置另存JSON；普通正文可手选分栏。复杂图表保留原图对照。",style = MaterialTheme.typography.bodySmall)
                }
                "photo_restore" -> {
                    ChipRow(listOf("高清","去模糊","降噪"),listOf("SUPER","DEBLUR","DENOISE").indexOf(restoreMode).coerceAtLeast(0)) { restoreMode=listOf("SUPER","DEBLUR","DENOISE")[it] }
                    if(restoreMode=="SUPER") {
                        ChipRow(listOf("2倍", "4倍"), if (restoreScale == 4) 1 else 0) { restoreScale = if (it == 1) 4 else 2 }
                        Text("2倍输入长边最多2000px，4倍最多1000px；输出最多1600万像素。",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text("处理强度 ${"%.0f".format(restoreStrength*100)}%"); Slider(restoreStrength,{ restoreStrength=it },valueRange=.1f..1f)
                        Text(if(restoreMode=="DEBLUR") "独立NAFNet模型；输入长边最多1600px，保持尺寸与透明度。去模糊适合轻中度模糊，过强时请降低强度。" else "非局部均值降噪；输入长边最多2200px，保持尺寸与透明度。强度过大会损失细小笔迹。",style=MaterialTheme.typography.bodySmall)
                    }
                }
                "portrait" -> {
                    ChipRow(listOf("透明", "白色", "蓝色", "红色"), background) { background = it }
                    ChipRow(com.localdoc.scanner.cv.PortraitSizes.sizes.map { it.label }, photoSize) { photoSize = it }
                    if (photoSize > 0) {
                        request.files.firstOrNull()?.let { PortraitCropGuide(it, photoSize, cropX, cropY) }
                        Text("左右构图"); Slider(cropX, { cropX = it })
                        Text("上下构图"); Slider(cropY, { cropY = it })
                        Text("绿色框是裁切范围；按300dpi参考像素输出，同时附A4实际尺寸PDF。请核对头顶与肩部留白。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "count_objects" -> ChipRow(ObjectCounting.labels, category) { category = it }
                "translate" -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(chineseToEnglish, { chineseToEnglish = it }); Text(if (chineseToEnglish) "中文 → 英文" else "英文 → 中文",modifier=Modifier.weight(1f)) }
                "pdf_long", "pdf_enhance" -> {
                    OutlinedTextField(pages, { pages = it }, label = { Text("页码范围") }, supportingText={ Text("如1-3,5；留空为全部页面") }, modifier = Modifier.fillMaxWidth())
                    if(request.tool.id=="pdf_enhance") {
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(pdfSuperResolution,{ pdfSuperResolution=it }); Text("2倍高清修复",modifier=Modifier.weight(1f)) }
                        Text("需要可选高清模型，逐页/逐图块保存进度；大文档处理时间较长。",style=MaterialTheme.typography.bodySmall)
                        if(pdfSuperResolution) {
                            ChipRow(listOf("快速","均衡","精细"),listOf(800,1200,2000).indexOf(pdfEnhanceSide).coerceAtLeast(0)) { pdfEnhanceSide=listOf(800,1200,2000)[it] }
                            Text("增强前长边最多 ${pdfEnhanceSide}px → 输出最多 ${pdfEnhanceSide*2}px；小尺寸页面可能低于此上限。均衡档适合先试少量页面。",style=MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Switch(pdfEnhanceSearchable,{ pdfEnhanceSearchable=it }); Text("生成可搜索文字层",modifier=Modifier.weight(1f)) }
                        Text("使用内置medium重新识别，可能有错字；请对照页面核对。",style=MaterialTheme.typography.bodySmall)
                    }
                }
                "qr_create" -> OutlinedTextField(qrText, { qrText = it.take(1800) }, label = { Text("文字或链接") }, modifier = Modifier.fillMaxWidth())
                "watermark_photo" -> {
                    OutlinedTextField(watermarkTime, { watermarkTime = it.take(60) }, label = { Text("时间文字") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(watermarkLocation, { watermarkLocation = it.take(120) }, label = { Text("地点（可留空或手动填）") }, modifier = Modifier.fillMaxWidth())
                    TextButton({ locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = !locating) { Text(if (locating) "正在取得位置…" else "使用设备位置") }
                    if (locationStatus.isNotBlank()) Text(locationStatus, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(watermarkText, { watermarkText = it.take(300) }, label = { Text("自定义水印文字") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    })
}
