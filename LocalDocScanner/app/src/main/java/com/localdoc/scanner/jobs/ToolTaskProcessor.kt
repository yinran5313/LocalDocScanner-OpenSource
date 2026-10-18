package com.localdoc.scanner.jobs

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.gson.reflect.TypeToken
import com.localdoc.scanner.cv.*
import com.localdoc.scanner.export.*
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.pdf.*
import com.localdoc.scanner.structure.*
import com.localdoc.scanner.util.AtomicFiles
import com.localdoc.scanner.util.ImageIo
import com.localdoc.scanner.barcode.BarcodeDecoder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext

/** Process immutable specifications; no activity, view model or Compose state is captured. */
internal class ToolTaskProcessor(private val context: Context, private val spec: ToolTaskSpec,
    private val password: CharArray?, private val progress: suspend (Int, Int, String) -> Unit) {
    internal var recognizerForTest: OcrEngine? = null
    private val gson = ToolTasks.gson
    private val root = ToolTasks.dir(context, spec.id)
    private val journal = ToolCheckpoints(root, gson)
    private val output = File(root, "outputs").apply { mkdirs() }
    private val inputs = spec.request.files
    private val first get() = inputs.first()
    private val started = android.os.SystemClock.elapsedRealtime()
    private var completed = 0
    private var total = 1
    private inline fun <reified T> parameter(name: String, fallback: T): T =
        spec.parameters[name]?.let { gson.fromJson<T>(it, object : TypeToken<T>() {}.type) } ?: fallback
    private fun file(name: String) = File(output, name)
    private fun version(value: Any) = ToolCheckpoints.digest(gson.toJson(value).toByteArray())
    private fun propagate(e: Exception) { if (e is CancellationException || e is ToolSliceEnded) throw e }

    private suspend inline fun <reified T> step(key: String, label: String,
        crossinline files: (T) -> List<File> = { emptyList() }, countUnit: Boolean = true, crossinline run: suspend () -> T): T {
        coroutineContext.ensureActive()
        if (android.os.SystemClock.elapsedRealtime() - started > 240_000) throw ToolSliceEnded()
        progress(completed, total, label)
        val receipt = journal.load(key)
        val restored: T? = receipt?.let { runCatching { gson.fromJson<T>(it.payload, object : TypeToken<T>() {}.type) }.getOrNull() }
        val value: T = if (restored != null) restored
        else {
            val generated = run()
            // A pause arriving during a native call preserves its completed output and receipt.
            withContext(NonCancellable) { journal.save(key, gson.toJson(generated), files(generated)) }
            generated
        }
        if(countUnit) completed++
        progress(completed, total, label)
        return value
    }
    private suspend fun pdfStep(key: String, name: String, block: (File) -> Boolean): File =
        step(key, "生成 $name", { listOf(it) }) {
            val target = file(name)
            AtomicFiles.write(target) { check(block(it)) { "$name 生成失败，已完成步骤保留" } }
            target
        }

    suspend fun run(): FlowOutcome = if (spec.action == "sensitive") step("sensitive", "medium查找本页敏感信息") {
        val page = parameter("pageIndex", 0)
        val found = SensitiveRegionDetector.detect(context, first, page, parameter("keywords", emptyList<String>()))
        FlowOutcome(listOf("结果" to "找到${found.size}个候选，请人工确认"), emptyList(), sensitiveRegions = found)
    } else if(spec.action=="translate_review") translateImage() else when (spec.request.tool.id) {
        "images_to_pdf" -> imagesToPdf()
        "image_ppt" -> {
            val order = parameter("order", inputs)
            require(order.isNotEmpty() && order.size <= 100 && order.size == inputs.size && order.toSet() == inputs.toSet()) { "图片顺序无效或超过100张" }
            total = order.size+1
            val pictures = order.mapIndexed { index, image -> step("ppt-image:$index", "准备幻灯片${index+1}", { listOfNotNull(it.file) }) {
                val bitmap = ImageIo.loadFromFile(image, 1600) ?: error("无法读取${image.name}")
                try { val target = file("幻灯图片${index + 1}.png")
                    AtomicFiles.write(target) { it.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) } }
                    com.localdoc.scanner.office.PptPicture(byteArrayOf(),bitmap.width,bitmap.height,target)
                } finally { bitmap.recycle() }
            } }
            require(pictures.sumOf { it.file!!.length() } <= 200L*1024*1024) { "图片总量超过200MB，请分组生成" }
            val target = step("image-ppt", "打包图片型PPT", { listOf(it) }) {
                file("照片演示文稿.pptx").also { out -> AtomicFiles.write(out) { com.localdoc.scanner.office.ImagePptWriter.write(context.assets.open("templates/untitled.pptx"),pictures,it) } }
            }
            FlowOutcome(listOf("页数" to "${pictures.size}", "内容" to "每页一张图片"),listOf(target))
        }
        "pdf_merge" -> {
            val order = parameter("order", inputs)
            require(order.size == inputs.size && order.toSet() == inputs.toSet()) { "合并顺序无效，请重新选择文件" }
            val result = pdfStep("merge", "合并.pdf") { PdfTools.merge(order, it) }
            FlowOutcome(listOf("输入" to "${order.size}份"), listOf(result))
        }
        "pdf_split" -> split()
        "pdf_pages" -> {
            val count = PdfTools.pageCount(first)
            val order = parameter("pdfPageOrder", emptyList<Int>())
            require(order.isNotEmpty() && order.distinct().size == order.size && order.all { it in 0 until count }) { "请保留至少一页，页面顺序必须有效" }
            val selected = PageSelection.parse(parameter("pageExportRange", ""), count).toSet()
            val export = order.filter { it in selected }; require(export.isNotEmpty()) { "选页中没有已保留的页面" }
            val turns = parameter("pdfPageTurns", emptyMap<Int, Int>())
            val result = pdfStep("page-manager", "页面整理.pdf") { target ->
                PDDocument.load(first).use { source ->
                    require(source.currentAccessPermission.canAssembleDocument()) { "此PDF不允许整理页面" }
                    PDDocument().use { document -> export.forEach { page -> val imported = document.importPage(source.getPage(page)); imported.rotation = (imported.rotation + (turns[page] ?: 0).coerceIn(0, 3) * 90) % 360 }; document.save(target) }
                }; true
            }
            FlowOutcome(listOf("页数" to "${export.size}", "顺序（原页码）" to export.joinToString { "${it + 1}" }), listOf(result))
        }
        "pdf_compress" -> {
            val tier = parameter("tier", 1).coerceIn(0, 3)
            val result = pdfStep("compress", "压缩.pdf") {
                PdfTools.compressPreservingContent(first, it, intArrayOf(110, 140, 200, 260)[tier], intArrayOf(58, 70, 86, 94)[tier]); true
            }
            FlowOutcome(listOf("压缩前" to "${first.length()}字节", "压缩后" to "${result.length()}字节"), listOf(result))
        }
        "pdf_to_images" -> pdfImages()
        "pdf_text" -> pdfText()
        "pdf_encrypt" -> {
            val secret = password ?: throw ToolPasswordNeeded()
            require(secret.isNotEmpty()) { "密码不能为空" }
            val result = pdfStep("encrypt", "加密副本.pdf") { PdfTools.encrypt(first, it, String(secret)) }
            FlowOutcome(listOf("结果" to "已生成256位加密副本"), listOf(result))
        }
        "pdf_sign" -> sign()
        "pdf_office" -> editPdf()
        "ocr", "card", "batch_extract", "table_xlsx" -> if (spec.action == "export") spreadsheet() else recognize()
        "barcode" -> barcode()
        "long_image" -> {
            val image = step("stitch", "拼接长图", { listOf(it) }) {
                val width = intArrayOf(1080, 720, 480)[parameter("width", 0).coerceIn(0, 2)]
                val bitmap = Stitch.verticalFiles(inputs, width)
                val target = file("长图.jpg")
                try { check(ImageIo.saveJpeg(bitmap, target, 88)); target } finally { bitmap.recycle() }
            }
            FlowOutcome(listOf("拼接页数" to "${inputs.size}"), listOf(image), listOf(image))
        }
        "image_edit" -> editImages()
        "portrait", "book_unwarp" -> modelImages()
        "translate" -> translateImage()
        "formula" -> recognizeFormula()
        "photo_restore" -> restorePhoto()
        "region_erase" -> eraseRegion()
        "structured_markdown" -> structuredMarkdown()
        "pdf_long", "pdf_enhance" -> batchPdfImage()
        "zip_import" -> extractZip()
        "qr_create" -> qrCreate()
        "count_objects" -> step("object-count", "识别并标记物体", { it.files }) {
            val category = parameter("category", 0).coerceIn(0, com.localdoc.scanner.models.ObjectCounting.labels.lastIndex)
            val source = ImageIo.loadFromFile(first, 2200) ?: error("无法读取图片")
            try {
                val boxes = com.localdoc.scanner.models.ObjectCounting.detect(context, source, category)
                val marked = source.copy(Bitmap.Config.ARGB_8888, true)
                try { val canvas = android.graphics.Canvas(marked); val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00A98D.toInt(); strokeWidth = source.width / 250f; textSize = source.width / 35f }
                    boxes.forEachIndexed { i, box -> paint.style = android.graphics.Paint.Style.STROKE; canvas.drawRect(box.left * source.width, box.top * source.height, box.right * source.width, box.bottom * source.height, paint)
                        paint.style = android.graphics.Paint.Style.FILL; canvas.drawText("${i + 1}", box.left * source.width, (box.top * source.height + paint.textSize).coerceAtMost(source.height.toFloat()), paint) }
                    val image = file("计数标记.jpg"); AtomicFiles.write(image) { check(ImageIo.saveJpeg(marked, it, 94)) }
                    val detail = file("计数位置.json"); AtomicFiles.text(detail, gson.toJson(boxes))
                    val report = file("计数复核.csv"); val label = com.localdoc.scanner.models.ObjectCounting.labels[category]
                    AtomicFiles.text(report, "类别,识别建议数量,人工复核数量,说明\n$label,${boxes.size},,请对照标记图核对遗漏与重复\n")
                    FlowOutcome(listOf("类别" to label, "识别建议数量" to "${boxes.size}"), listOf(image, report, detail), listOf(image))
                } finally { marked.recycle() }
            } finally { source.recycle() }
        }
        "watermark_photo" -> step("watermark-photo", "生成水印照片", { it.files }) {
            val text = listOf(parameter("watermarkTime", ""), parameter("watermarkLocation", ""), parameter("watermarkText", "")).filter(String::isNotBlank).joinToString("\n")
            val bitmap = ImageIo.loadFromFile(first, 3200) ?: error("无法读取照片")
            try { val result = ImageWatermark.render(bitmap, text)
                try { val target = file("水印照片.jpg"); AtomicFiles.write(target) { check(ImageIo.saveJpeg(result, it, 94)) }
                    FlowOutcome(listOf("结果" to "已生成水印副本"), listOf(target), listOf(target))
                } finally { result.recycle() }
            } finally { bitmap.recycle() }
        }
        "pdf_compare" -> step("compare", "逐页对比PDF", { it.files }) {
            require(inputs.size == 2) { "请选择恰好两份PDF" }
            val target = file("PDF差异.pdf")
            var changed = 0
            var notes = emptyList<String>()
            AtomicFiles.write(target) {
                val result = PdfComparison.compare(context, inputs[0], inputs[1], it)
                changed = result.first; notes = result.second
            }
            val report = file("PDF对比.txt"); AtomicFiles.text(report, notes.joinToString("\n"))
            FlowOutcome(listOf("差异页" to "$changed"), listOf(report, target))
        }
        else -> error("暂不支持此工具的后台处理")
    }

    private suspend fun imagesToPdf(): FlowOutcome {
        total = inputs.size + 1
        val qualityV5 = parameter("qualityV5", -1)
        val quality = if (qualityV5 >= 0) intArrayOf(3200, 2000, 1100)[qualityV5.coerceIn(0,2)] else intArrayOf(2400,1600,1100)[parameter("quality",1).coerceIn(0,2)]
        val paperV5 = parameter("paperV5", "")
        val size = if (paperV5.isNotBlank()) PdfExporter.PageSize.valueOf(paperV5) else if(parameter("pageSize",0)==1) PdfExporter.PageSize.FIT_IMAGE else PdfExporter.PageSize.A4
        val pages = inputs.mapIndexed { i, source -> pdfStep("image-pdf:$i", "第${i + 1}页.pdf") {
            it.outputStream().use { stream -> PdfExporter.exportFiles(listOf(source), stream, size, quality) }
        } }
        val result = pdfStep("pack:${version(pages.map { ToolCheckpoints.hash(it) })}", "图片合并.pdf") { PdfTools.merge(pages, it) }
        return FlowOutcome(listOf("页数" to "${pages.size}"), listOf(result))
    }
    private suspend fun split(): FlowOutcome {
        val count = PdfTools.pageCount(first); require(count > 0) { "PDF无法读取，请先解锁" }
        val ranges = if (parameter("mode", 0) == 0) {
            val n = parameter("perFile", "1").toIntOrNull(); require(n != null && n > 0) { "每份页数必须大于0" }
            (1..count).chunked(n).map { it.first()..it.last() }
        } else parameter("ranges", "1-1").split(',', '，').map { part ->
            require(part.trim().matches(Regex("[0-9]+(\\s*-\\s*[0-9]+)?"))) { "页范围无效：$part" }
            val ends = part.trim().split('-').map { it.trim().toInt() }
            val a = ends.first(); val b = ends.last()
            require(a in 1..count && b in a..count) { "页范围超出文档：$part" }; a..b
        }
        total = ranges.size
        val files = ranges.mapIndexed { i, range -> pdfStep("split:$i", "拆分${i + 1}_${range.first}-${range.last}.pdf") { target ->
            PDDocument.load(first).use { source -> PDDocument().use { result ->
                range.forEach { result.importPage(source.getPage(it - 1)) }; result.save(target)
            } }; true
        } }
        return FlowOutcome(listOf("原文档页数" to "$count", "拆出份数" to "${files.size}"), files)
    }
    private suspend fun pdfImages(): FlowOutcome {
        val count = PdfTools.pageCount(first); require(count > 0) { "PDF无法读取，请先解锁" }
        val selected = PageSelection.parse(parameter("selectedPages", ""), count)
        total = selected.size
        val dpi = intArrayOf(200, 150, 110)[parameter("dpi", 1).coerceIn(0, 2)]
        val failures = mutableListOf<String>()
        val images = mutableListOf<File>()
        for (index in selected) try {
            images += step("render:$index", "导出第${index + 1}/$count 页", { listOf(it) }) {
                val bitmap = PdfTools.renderPageAtDpi(first, index, dpi) ?: error("无法读取页面")
                try { file("第${index + 1}页.jpg").also { check(ImageIo.saveJpeg(bitmap, it, 94)) } } finally { bitmap.recycle() }
            }
        } catch (e: Exception) { propagate(e); failures += "第${index + 1}页：${e.message}" }
        return FlowOutcome(listOf("导出页数" to "${images.size}/${selected.size}") + failureSummary(failures), images, images, failures = failures)
    }

    private suspend fun modelImages(): FlowOutcome {
        total = inputs.size
        val split = spec.request.tool.id == "book_unwarp" && parameter("splitSpread",false)
        total = inputs.size * if (split) 2 else 1
        val images = inputs.flatMapIndexed { index, source -> (0 until if (split) 2 else 1).map { side -> step("model-image:$index:$side", "处理第${index+1}张${if (split) if (side==0) "左页" else "右页" else ""}", { listOf(it) }) {
            val portrait = spec.request.tool.id == "portrait"
            val bitmap = ImageIo.loadFromFile(source,if (portrait) 2400 else 1400) ?: error("无法读取图片")
            var page = bitmap
            try {
                if (split) {
                    val splitX = (bitmap.width*parameter("splitRatio",.5f).coerceIn(.3f,.7f)).toInt().coerceIn(1,bitmap.width-1)
                    page = if (side==0) Bitmap.createBitmap(bitmap,0,0,splitX,bitmap.height) else Bitmap.createBitmap(bitmap,splitX,0,bitmap.width-splitX,bitmap.height)
                }
                val result = if (portrait) com.localdoc.scanner.models.ModelInference.portrait(context,page,listOf(null,android.graphics.Color.WHITE,0xFF438EDB.toInt(),0xFFD94343.toInt())[parameter("background",0).coerceIn(0,3)])
                    else com.localdoc.scanner.models.ModelInference.unwarp(context,page)
                try {
                    val format = com.localdoc.scanner.cv.PortraitSizes.sizes[parameter("photoSize",0).coerceIn(com.localdoc.scanner.cv.PortraitSizes.sizes.indices)]
                    var cropped = result
                    try {
                        if (portrait && format.width>0) {
                            val region = com.localdoc.scanner.cv.PortraitSizes.crop(result.width,result.height,format,parameter("photoCropX",.5f),parameter("photoCropY",.5f))
                            val part = Bitmap.createBitmap(result,region.left,region.top,region.width,region.height)
                            cropped = Bitmap.createScaledBitmap(part,format.width,format.height,true)
                            if (part !== result && part !== cropped) part.recycle()
                        }
                        file("${if (portrait) "人像" else "展平"}${index+1}${if (split) "-${side+1}" else ""}.png").also { target -> AtomicFiles.write(target) { it.outputStream().use { out -> check(cropped.compress(Bitmap.CompressFormat.PNG,100,out)) } } }
                    } finally { if (cropped !== result) cropped.recycle() }
                } finally { result.recycle() }
            } finally { if (page !== bitmap) page.recycle(); bitmap.recycle() }
        } } }
        val outputs = images.toMutableList()
        val format = com.localdoc.scanner.cv.PortraitSizes.sizes[parameter("photoSize", 0).coerceIn(com.localdoc.scanner.cv.PortraitSizes.sizes.indices)]
        if (spec.request.tool.id == "portrait" && format.width > 0) outputs += file("证件照实际尺寸.pdf").also {
            com.localdoc.scanner.export.CardSheet.write(images.take(1), com.localdoc.scanner.structure.CardTemplates.Template(format.label,format.widthMm,format.heightMm,false),it)
        }
        return FlowOutcome(listOf("结果" to "${images.size}张，请对照原图复核"), outputs, images)
    }

    private suspend fun recognizeFormula(): FlowOutcome {
        total = 1
        val latex = step("formula", "识别单条数学公式") {
            val source = ImageIo.loadFromFile(first,1600) ?: error("图片无法读取")
            try { com.localdoc.scanner.models.FormulaRecognition.recognize(context,source) } finally { source.recycle() }
        }
        return FlowOutcome(listOf("结果" to "请校订LaTeX并检查公式预览"),emptyList(),copyText = latex)
    }

    private suspend fun restoredTiles(source: Bitmap, scale: Int, scope: String, countTiles: Boolean = true): Bitmap {
        val geometry = com.localdoc.scanner.models.RestorationGeometry
        val pack = com.localdoc.scanner.models.ModelCatalog.get(context, "restore")
        val count = geometry.tileCount(source.width,source.height)
        require(root.usableSpace > source.width.toLong()*source.height*scale*scale*8 + 32L*1024*1024) { "空间不足，请至少留出图块与输出文件所需空间" }
        if(countTiles) total += count
        return com.localdoc.scanner.models.PhotoRestoration.restore(context,source,scale) { tile,infer ->
            val key="sr-tile:$scope:${pack.version}:$scale:${source.width}x${source.height}:${tile.x}:${tile.y}"
            val index=tile.y/geometry.CORE*((source.width+geometry.CORE-1)/geometry.CORE)+tile.x/geometry.CORE+1
            val label=(if(scope.startsWith("pdf:")) "第${scope.substringAfter(':').toInt()+1}页 · " else "")+"高清图块 $index/$count"
            val cached=step(key,label,{ listOf(it) },countUnit=countTiles) {
                val bitmap=infer()
                try { File(root,"tiles/${ToolCheckpoints.digest(key.toByteArray())}.png").also { target ->
                    AtomicFiles.write(target) { staged -> staged.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) } }
                } } finally { bitmap.recycle() }
            }
            requireNotNull(android.graphics.BitmapFactory.decodeFile(cached.absolutePath)) { "已完成图块无法读取，请重新提交" }
        }
    }

    private suspend fun restorePhoto(): FlowOutcome {
        total = 1
        val mode=parameter("restoreMode","SUPER")
        require(mode in listOf("SUPER","DEBLUR","DENOISE")) { "图像处理模式无效" }
        val scale=parameter("restoreScale",2)
        val strength=parameter("restoreStrength",.7f)
        require(strength.isFinite() && strength in 0f..1f)
        val label=when(mode) { "DEBLUR" -> "去模糊"; "DENOISE" -> "降噪"; else -> "高清修复" }
        val output=step("restore:$mode","本地$label",{ listOf(it) }) {
            val source=ImageIo.loadExactFromFile(first,when(mode) { "DEBLUR" -> 1600; "DENOISE" -> 2200; else -> com.localdoc.scanner.models.RestorationGeometry.inputSide(scale) }) ?: error("图片读取失败")
            try {
                val fixed=when(mode) { "DEBLUR" -> deblurTiles(source,strength); "DENOISE" -> com.localdoc.scanner.cv.PhotoDenoise.run(source,strength); else -> restoredTiles(source,scale,"photo") }
                try { file("$label.png").also { target -> AtomicFiles.write(target) { it.outputStream().use { out -> check(fixed.compress(Bitmap.CompressFormat.PNG,100,out)) } } } } finally { fixed.recycle() }
            } finally { source.recycle() }
        }
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeFile(output.absolutePath,bounds)
        return FlowOutcome(listOf("处理" to when(mode) { "DEBLUR" -> "NAFNet分块去模糊；已完成图块可续跑"; "DENOISE" -> "OpenCV非局部均值降噪"; else -> "Real-ESRGAN分块增强；已完成图块可续跑" }, "输出尺寸" to "${bounds.outWidth} × ${bounds.outHeight} 像素", "复核" to "请对照原图检查文字与细节"),listOf(output),listOf(output))
    }

    private suspend fun deblurTiles(source: Bitmap,strength: Float): Bitmap {
        val core=com.localdoc.scanner.models.PhotoDeblur.CORE
        val count=((source.width+core-1)/core)*((source.height+core-1)/core)
        val pack=com.localdoc.scanner.models.ModelCatalog.get(context,"deblur")
        require(root.usableSpace>source.width.toLong()*source.height*12+32L*1024*1024) { "去模糊图块与结果所需空间不足" }
        total+=count
        return com.localdoc.scanner.models.PhotoDeblur.restore(context,source,strength) { tile,infer ->
            val key="deblur-tile:${pack.version}:$strength:${source.width}x${source.height}:${tile.x}:${tile.y}"
            val index=tile.y/core*((source.width+core-1)/core)+tile.x/core+1
            val cached=step(key,"去模糊图块 $index/$count",{ listOf(it) }) {
                val bitmap=infer()
                try { File(root,"tiles/${ToolCheckpoints.digest(key.toByteArray())}.png").also { target ->
                    AtomicFiles.write(target) { staged -> staged.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) } }
                } } finally { bitmap.recycle() }
            }
            requireNotNull(android.graphics.BitmapFactory.decodeFile(cached.absolutePath)) { "去模糊图块缓存无效，请重新提交" }
        }
    }

    private suspend fun eraseRegion(): FlowOutcome {
        total = 1
        val output = step("erase", "修补选中区域", { listOf(it) }) {
            val source = ImageIo.loadFromFile(first, 2200) ?: error("图片读取失败")
            try {
                val strokes = parameter<List<List<com.localdoc.scanner.pdf.PdfInkPoint>>>("eraseStrokes",emptyList())
                val fixed = if(parameter("eraseLearned",false)) com.localdoc.scanner.models.LearnedRegionRepair.repair(context,source,strokes,parameter("eraseBrush",0.035f)) else com.localdoc.scanner.cv.RegionRepair.repair(source,strokes,parameter("eraseBrush",0.035f))
                try { file("区域修补.png").also { target -> AtomicFiles.write(target) { it.outputStream().use { out -> check(fixed.compress(Bitmap.CompressFormat.PNG,100,out)) } } } } finally { fixed.recycle() }
            } finally { source.recycle() }
        }
        return FlowOutcome(listOf("处理" to if(parameter("eraseLearned",false)) "LaMa背景修补；请核对选区边缘与细节" else "本地邻域修补；请核对选区边缘与文字"),listOf(output),listOf(output))
    }

    private suspend fun translateImage(): FlowOutcome {
        val supplied=parameter("translationSource", "").trim()
        total = if(supplied.isBlank()) 2 else 1
        val sourceOcr = if(supplied.isNotBlank()) OcrOutcome(supplied,supplied.lines().size,0,0) else step("translate-ocr-v52", "medium识别原文") {
            // Preserve a completed V5.1 text-only receipt instead of running OCR again.
            val legacy=journal.load("translate-ocr")?.payload?.let {runCatching {com.google.gson.JsonParser.parseString(it).takeIf {json ->json.isJsonPrimitive}?.asString}.getOrNull()}
            if(!legacy.isNullOrBlank()) return@step OcrOutcome(legacy,legacy.lines().size,0,0)
            val bitmap = ImageIo.loadFromFile(first, 3600) ?: error("无法读取图片")
            val engine = recognizerForTest ?: PaddleOcrEngine(context)
            try { engine.recognize(bitmap, true).also { require(it.text.isNotBlank()) { "没有识别到文字" } } }
            finally { bitmap.recycle(); engine.close() }
        }
        val sourceText=sourceOcr.text
        val translated = step("translate", "本地翻译") {
            com.localdoc.scanner.models.LocalTranslation.translate(context, sourceText, parameter("chineseToEnglish", true))
        }
        val text = "原文\n$sourceText\n\n译文\n$translated"
        val target = file("翻译结果.txt"); AtomicFiles.text(target, text)
        return FlowOutcome(listOf("方向" to if(parameter("chineseToEnglish",true)) "中文→英文" else "英文→中文", "结果" to "译文请核对姓名、数字和专有名词"), listOf(target), copyText = text,
            reviewPages=listOf(ReviewPage(first.absolutePath,0,sourceText,mapOf("译文" to translated),emptyList(),sourceBoxes=sourceOcr.boxes)))
    }

    private suspend fun structuredMarkdown(): FlowOutcome {
        val counts = inputs.map { if (it.extension.equals("pdf", true)) PdfTools.pageCount(it) else 1 }
        val withImages=parameter("mdSourceImages",true)
        val layout=parameter("mdLayout",false)
        total = counts.sum() * ((if (layout || parameter("mdTable",false)) 2 else 1) + (if(withImages) 1 else 0)) + 1
        val failures = mutableListOf<String>()
        val parts = mutableListOf<String>(); val attachments = mutableListOf<File>(); val engine = recognizerForTest ?: PaddleOcrEngine(context)
        try { for ((fileIndex, source) in inputs.withIndex()) {
            require(counts[fileIndex] > 0) { "PDF无法读取，请先解锁" }
            for (page in 0 until counts[fileIndex]) {
                val boxes = step("md-ocr:$fileIndex:$page", "识别 ${source.name} 第${page + 1}页") {
                    val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, page, 3000) else ImageIo.loadFromFile(source, 3600)
                    requireNotNull(bitmap) { "无法读取图像" }
                    try { engine.recognize(bitmap, true).boxes } finally { bitmap.recycle() }
                }
                var modelTable: com.localdoc.scanner.models.ModelTable? = null
                var layoutPage: MarkdownPageOutput?=null
                val content = if(layout) {
                    layoutPage=step("md-layout:$fileIndex:$page","区分图文区域并恢复阅读顺序",{ it.files }) {
                        val bitmap=if(source.extension.equals("pdf",true)) PdfTools.renderPage(source,page,2600) else ImageIo.loadExactFromFile(source,2600)
                        requireNotNull(bitmap)
                        try { val regions=com.localdoc.scanner.models.DocumentLayoutModel.detect(context,bitmap)
                            StructuredMarkdownPage.render(context,bitmap,boxes,regions,parameter("mdTable",false),parameter("mdColumns","AUTO"),output,"${fileIndex+1}-${page+1}")
                        } finally { bitmap.recycle() }
                    }
                    attachments+=layoutPage.files
                    failures+=layoutPage.warnings.map { "${source.name} 第${page+1}页：$it" }
                    layoutPage.markdown
                } else if (parameter("mdTable",false)) {
                    try { modelTable = step("md-table:$fileIndex:$page", "恢复本页表格结构") {
                        val bitmap = if (source.extension.equals("pdf",true)) PdfTools.renderPage(source,page,2600) else ImageIo.loadFromFile(source,3200)
                        requireNotNull(bitmap)
                        try { com.localdoc.scanner.models.TableModel.recover(context,bitmap,boxes) } finally { bitmap.recycle() }
                    }
                    val remaining=boxes.filter { word -> modelTable.cells.none { c -> word.left<c.right && word.right>c.left && word.top<c.bottom && word.bottom>c.top } }
                    com.localdoc.scanner.structure.LayoutMarkdown.modelTable(modelTable)+if(remaining.isNotEmpty()) "\n\n"+com.localdoc.scanner.structure.LayoutMarkdown.render(remaining,parameter("mdColumns","AUTO")) else ""
                    } catch (e: Exception) { propagate(e); failures += "${source.name} 第${page+1}页：表格未恢复，已保留文字；${e.message}"; com.localdoc.scanner.structure.LayoutMarkdown.render(boxes,parameter("mdColumns","AUTO")) }
                } else com.localdoc.scanner.structure.LayoutMarkdown.render(boxes,parameter("mdColumns","AUTO"))
                val sourceImage=if(withImages) step("md-source:$fileIndex:$page","保存第${page+1}页原图附件",{ listOf(it) }) {
                    val bitmap=if(source.extension.equals("pdf",true)) PdfTools.renderPage(source,page,1600) else ImageIo.loadExactFromFile(source,1600)
                    requireNotNull(bitmap) { "原页图像无法读取" }
                    try { file("source-${fileIndex+1}-${page+1}.jpg").also { check(ImageIo.saveJpeg(bitmap,it,92)) } } finally { bitmap.recycle() }
                } else null
                sourceImage?.let { attachments+=it }
                val sourceName=(spec.request.names.getOrNull(fileIndex) ?: source.name).replace("\n"," ").replace("\r"," ")
                parts += "## $sourceName · 第${page+1}页\n\n" + content + (sourceImage?.let { "\n\n[查看第${page+1}页原图](${it.name})\n\n![第${page+1}页原图](${it.name})" } ?: "")
                val locations = file("来源${fileIndex + 1}-${page + 1}.json")
                AtomicFiles.text(locations, gson.toJson(mapOf("source" to (spec.request.names.getOrNull(fileIndex) ?: source.name), "page" to page + 1, "boxes" to boxes, "tableCells" to modelTable?.cells, "merges" to modelTable?.merges, "regions" to layoutPage?.regions)))
                attachments += locations
            }
        } } finally { engine.close() }
        val text = parts.joinToString("\n\n---\n\n"); val target = file("识别整理.md"); AtomicFiles.text(target, text)
        val bundle=step("md-bundle","打包Markdown与原页附件",{ listOf(it) }) {
            file("Markdown与原图.zip").also { MarkdownBundle.write(listOf(target)+attachments,it) }
        }
        return FlowOutcome(listOf("结果" to "结构需复核；文字/表格位置见JSON", "分享" to "底部保存/分享默认使用完整ZIP；单个文件可从输出菜单操作"), listOf(target,bundle)+attachments, copyText=text, failures=failures,primaryFiles=listOf(bundle),libraryFiles=listOf(target,bundle))
    }

    private suspend fun batchPdfImage(): FlowOutcome {
        val selected=PageSelection.parse(parameter("selectedPages",""),PdfTools.pageCount(first))
        val enhance=spec.request.tool.id=="pdf_enhance"
        val sr=enhance && parameter("pdfSuperResolution",false)
        val searchable=enhance && parameter("pdfEnhanceSearchable",false)
        val srSide=parameter("pdfEnhanceSide",1200)
        require(!sr || srSide in listOf(800,1200,2000)) { "高清档尺寸无效" }
        total=selected.size*(if(searchable) 2 else 1)+1
        val images=selected.map { page -> step("batch-render:$page","处理第${page+1}页",{ listOf(it) }) {
            val bitmap=PdfTools.renderPage(first,page,if(sr) srSide else if(enhance) 2600 else 1440) ?: error("页面无法读取")
            var result=bitmap
            try {
                if(enhance) result=applyFilter(bitmap,ScanFilter.AUTO)
                if(sr) {
                    val restored=restoredTiles(result,2,"pdf:$page",countTiles=false)
                    if(result!==bitmap) result.recycle()
                    result=restored
                }
                file("选页${page+1}.jpg").also { check(ImageIo.saveJpeg(result,it,94)) }
            } finally { if(result!==bitmap) result.recycle(); bitmap.recycle() }
        } }
        if(enhance) {
            val ocr=if(searchable) {
                val engine=recognizerForTest ?: PaddleOcrEngine(context)
                try { images.mapIndexed { index,image -> step("pdf-enhance-ocr:${selected[index]}","medium识别第${selected[index]+1}页") {
                    val bitmap=ImageIo.loadExactFromFile(image,3600) ?: error("增强图片无法读取")
                    try { engine.recognize(bitmap,true) } finally { bitmap.recycle() }
                } } } finally { engine.close() }
            } else emptyList()
            val pages=PDDocument.load(first).use { original -> selected.mapIndexed { index,page ->
                val geometry=PdfPageGeometry(original.getPage(page))
                SearchablePdfPage(images[index],ocr.getOrNull(index)?.text.orEmpty(),ocr.getOrNull(index)?.boxes.orEmpty(),geometry.width,geometry.height)
            } }
            val pdf=pdfStep("enhanced-pdf","增强副本.pdf") { target -> target.outputStream().use { out ->
                SearchablePdfExporter.export(context,pages,out,PdfExporter.PageSize.FIT_IMAGE,if(sr) 4000 else 2600)
            } }
            return FlowOutcome(listOf("页数" to "${images.size}", "增强" to if(sr) "基础增强 + 2倍高清修复" else "基础增强", "结果" to if(searchable) "图片型副本 + 新medium文字层；保持原可见页面尺寸" else "图片型副本；保持原可见页面尺寸", "原稿" to "原PDF保留；原表单、批注与文字层不迁移"),listOf(pdf))
        }
        val files=step("long-tiles","拼接分块长图",{ it }) { com.localdoc.scanner.cv.LongImageTiles.create(images,output) }
        return FlowOutcome(listOf("页数" to "${images.size}","长图分块" to "${files.size}张"),files,files)
    }

    private suspend fun extractZip(): FlowOutcome {
        require(first.extension.equals("zip",true)) { "请选择ZIP格式压缩包" }
        val files=mutableListOf<File>(); val failures=mutableListOf<String>(); val folders=mutableMapOf<String,String>(); val labels=mutableMapOf<String,String>(); val origins=mutableMapOf<String,String>()
        var totalBytes=0L
        val group=("解压 · "+first.nameWithoutExtension.take(30)).take(45)
        val archive=try { java.util.zip.ZipFile(first) } catch(e: java.util.zip.ZipException) { throw IllegalArgumentException("压缩包格式损坏或含不安全路径，请选择有效ZIP",e) }
        archive.use { zip ->
            val entries=zip.entries().asSequence().toList(); require(entries.size<=2000) { "压缩包条目超过2000，请分包处理" }
            total=entries.count { !it.isDirectory }+1
            for ((index,entry) in entries.withIndex()) {
                if (entry.isDirectory) continue
                try {
                    val name=entry.name.replace('\\','/'); val segments=name.split('/')
                    require(name.length<=1024 && !name.startsWith('/') && !name.contains(':') && segments.none { it in setOf("..",".") || it.any(Char::isISOControl) }) { "路径无效，已跳过此条目" }
                    val label=com.localdoc.scanner.util.FileNames.safe(segments.last())
                    require(label.isNotBlank() && label !in setOf(".","..")) { "文件名称无效" }
                    require(entry.size in 0..120L*1024*1024 && totalBytes+entry.size<=512L*1024*1024) { "解压大小超出限制" }
                    val target=step("zip-entry:$index", "解压 ${label.take(40)}", { listOf(it) }) {
                        file("${index+1}_$label").also { dest -> AtomicFiles.write(dest) { part ->
                            var bytes=0L; val checksum=java.util.zip.CRC32()
                            zip.getInputStream(entry).use { input -> part.outputStream().buffered().use { out ->
                                val buffer=ByteArray(64*1024)
                                while(true) { coroutineContext.ensureActive(); val n=input.read(buffer); if(n<0)break
                                    bytes+=n; require(bytes<=120L*1024*1024 && totalBytes+bytes<=512L*1024*1024 && output.usableSpace>16L*1024*1024) { "解压大小超过限制或空间不足" }; checksum.update(buffer,0,n); out.write(buffer,0,n)
                                }
                            } }
                            require(bytes==entry.size && checksum.value==entry.crc) { "文件长度或CRC与ZIP目录不一致" }
                        } }
                    }
                    totalBytes+=target.length(); files+=target
                    labels[target.absolutePath]=label
                    folders[target.absolutePath]=(group+segments.dropLast(1).filter(String::isNotBlank).joinToString("/",prefix=if(segments.size>1) "/" else "")).take(80)
                    origins[target.name]=name
                } catch(e: Exception) { propagate(e); failures+="条目${index+1} ${entry.name.take(80)}：${e.message}" }
            }
        }
        val mapping=step("zip-directory", "保存原目录与失败清单", { listOf(it) }) {
            file("解压目录.json").also { AtomicFiles.text(it,gson.toJson(mapOf("archive" to first.name,"paths" to origins,"failures" to failures))) }
        }
        files+=mapping; folders[mapping.absolutePath]=group
        return FlowOutcome(listOf("文件" to "${files.size-1}个", "大小" to "$totalBytes 字节", "目录" to "导入文档库后按原目录分组；过长目录名截断，完整目录见JSON"),files,failures=failures,fileFolders=folders,fileLabels=labels)
    }

    private suspend fun qrCreate(): FlowOutcome = step("qr-create", "生成二维码", { it.files }) {
        val text = parameter("qrText", ""); require(text.isNotBlank() && text.length <= 1800) { "请输入1至1800字符" }
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 900, 900,
            mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 2))
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val target = file("二维码.png")
        try { bitmap.setPixels(IntArray(900 * 900) { i -> if (matrix[i % 900, i / 900]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 900, 0, 0, 900, 900)
            AtomicFiles.write(target) { it.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } }
        } finally { bitmap.recycle() }
        FlowOutcome(listOf("结果" to "二维码已生成"), listOf(target), listOf(target), copyText = text)
    }
    private suspend fun pdfText(): FlowOutcome {
        PDDocument.load(first).use { document ->
            total = document.numberOfPages + 1
            val pages = (0 until document.numberOfPages).map { index -> step("text:$index", "提取第${index + 1}页") {
                PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(document).trim()
            } }
            val text = pages.joinToString("\n\n")
            if (text.isBlank()) return FlowOutcome(listOf("结果" to "没有文字层，请用文字识别"), emptyList())
            val target = step("text-pack:${version(pages)}", "保存提取文字", { listOf(it) }) {
                file("提取文字.txt").also { AtomicFiles.text(it, text) }
            }
            return FlowOutcome(listOf("字数" to "${text.length}"), listOf(target), copyText = text)
        }
    }
    private suspend fun sign(): FlowOutcome = step("sign", "生成数字签名", { it.files }) {
        val uri = parameter<Uri?>("signingStoreUri", null) ?: error("请选择证书文件")
        val secret = password ?: throw ToolPasswordNeeded()
        var certificate = ""
        val target = file("数字签名.pdf")
        AtomicFiles.write(target) { staged ->
            context.contentResolver.openInputStream(uri)?.use {
                val info = PdfDigitalSignature.sign(first, staged, it, secret,
                    parameter("signingAlias", ""), parameter("signingReason", ""), parameter("signingName", ""))
                certificate = info.subject
            } ?: error("无法读取证书文件")
        }
        FlowOutcome(listOf("结果" to "已生成数字签名副本", "证书" to certificate), listOf(target))
    }
    private suspend fun editPdf(): FlowOutcome {
        val edits = parameter<List<PendingPdfEdit>>("edits", emptyList()); require(edits.isNotEmpty()) { "编辑清单为空" }
        total = edits.size
        var input = first
        for ((index, edit) in edits.withIndex()) {
            val previous = input
            input = pdfStep("edit:$index:${ToolCheckpoints.hash(previous)}", "编辑${index + 1}.pdf") { target ->
                PdfEditPipeline.apply(context, previous, target, edit)
            }
        }
        return FlowOutcome(listOf("操作" to edits.joinToString("、") { it.label }, "结果" to "已生成编辑副本"), listOf(input))
    }

    private data class RecognizedPage(val source: String, val page: Int, val image: File?, val text: String, val boxes: List<OcrTextBox>)
    private suspend fun recognize(): FlowOutcome {
        val structured = spec.request.tool.id in setOf("batch_extract", "table_xlsx")
        val card = spec.request.tool.id == "card"
        val language = OcrLanguage.fromCode(parameter("ocrLanguage", "AUTO"))
        val medium = true // All new and legacy tool requests use the bundled medium model.
        val searchable = !structured && !card && parameter("searchable", true)
        val counts = inputs.map { if (it.extension.equals("pdf", true)) PdfTools.pageCount(it) else 1 }
        total = counts.sum() * (if (structured && parameter("useTableModel",false)) 2 else 1) + if (structured || card) 0 else if (searchable) 2 else 1
        val failures = mutableListOf<String>()
        val pages = mutableListOf<RecognizedPage>()
        val reviews = mutableListOf<ReviewPage>()
        val engine = recognizerForTest ?: PaddleOcrEngine(context)
        try {
            for ((fileIndex, source) in inputs.withIndex()) {
                val count = counts[fileIndex]
                if (count == 0) failures += "${source.name}：PDF无法读取，请先解锁"
                for (index in 0 until count) {
                    try {
                        val value = step("ocr:$fileIndex:$index", "识别 ${source.name} 第${index + 1}/$count 页", { it.image?.let(::listOf).orEmpty() }) {
                            val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, index, if (medium) 3000 else 2000)
                                else ImageIo.loadFromFile(source, if (medium) 3600 else 2400)
                            requireNotNull(bitmap) { "无法读取图片" }
                            try {
                                val result = engine.recognize(bitmap, medium, language)
                                require(result.text.isNotBlank()) { "未识别到文字，请调整图片或录入" }
                                val image = if (searchable) file("OCR_${fileIndex}_${index}.jpg").also { check(ImageIo.saveJpeg(bitmap, it, 94)) } else null
                                RecognizedPage(source.absolutePath, index, image, result.text, result.boxes)
                            } finally { bitmap.recycle() }
                        }
                        pages += value
                        if (structured) {
                            var rows = TableRecovery.fromBoxes(value.boxes).ifEmpty { TableRecovery.fromText(value.text) }
                            var table: com.localdoc.scanner.models.ModelTable? = null; var note = "基础行列恢复，请复核"
                            if (parameter("useTableModel", false)) {
                                try { val recovered = step("table-region-v52:$fileIndex:$index", "定位表格并恢复行列") {
                                    val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, index, 2600) else ImageIo.loadFromFile(source, 3200)
                                    requireNotNull(bitmap)
                                    try { PageTableRecovery.recover(context, bitmap, value.boxes) } finally { bitmap.recycle() }
                                }; table = recovered.table; rows = table.rows; note = recovered.note }
                                catch (e: Exception) { propagate(e); note = "结构模型未恢复：${e.message}；已保留基础行列和medium文字结果" }
                            }
                            if (spec.request.tool.id == "batch_extract" && parameter("batchKind",0) == 0) rows = com.localdoc.scanner.structure.InvoiceReview.itemRows(rows)
                            reviews += ReviewPage(value.source, index, value.text,
                                if (spec.request.tool.id == "table_xlsx") emptyMap() else fields(value.text), rows,
                                columnTypes = com.localdoc.scanner.structure.ColumnInference.suggest(rows), merges = if (spec.request.tool.id == "table_xlsx") table?.merges else null, sourceBoxes = value.boxes, modelCells = if (spec.request.tool.id == "table_xlsx") table?.cells else null, layoutNote = note)
                        }
                    } catch (e: Exception) {
                        propagate(e); val error = "${source.name} 第${index + 1}页：${e.message}"; failures += error
                        if (structured) reviews += ReviewPage(source.absolutePath, index, "", emptyMap(), emptyList(), error)
                    }
                }
            }
        } finally { withContext(NonCancellable) { engine.close() } }
        val text = pages.joinToString("\n\n") { if (card) it.text else "【${File(it.source).name} 第${it.page + 1}页】\n${it.text}" }
        if (structured || card) return FlowOutcome(listOf("识别页数" to "${pages.size}/${counts.sum()}") + failureSummary(failures),
            emptyList(), copyText = text, reviewPages = reviews, failures = failures)
        val outputs = mutableListOf<File>()
        if (text.isNotBlank()) outputs += step("ocr-text:${version(pages)}", "保存识别文字", { listOf(it) }) {
            file("识别文字.txt").also { AtomicFiles.text(it, text) }
        }
        if (searchable && pages.isNotEmpty()) {
            if (failures.isEmpty()) try {
                outputs += pdfStep("ocr-pdf:${version(pages)}", "可搜索.pdf") { target ->
                    target.outputStream().use { stream -> SearchablePdfExporter.export(context,
                        pages.map { SearchablePdfPage(requireNotNull(it.image), it.text, it.boxes) }, stream) }
                }
            } catch (e: Exception) { propagate(e); failures += "可搜索PDF生成失败：${e.message}，文字和页面结果已保留" }
            else failures += "存在未成功页面，可搜索PDF暂不合并；续跑成功后再生成完整PDF"
        }
        return FlowOutcome(listOf("字数" to "${text.length}", "识别页数" to "${pages.size}/${counts.sum()}") + failureSummary(failures), outputs,
            copyText = text, failures = failures, ocrReviewTaskId = spec.id,
            ocrReviewKeys = pages.map { "ocr:${inputs.indexOf(File(it.source))}:${it.page}" })
    }
    private fun fields(raw: String) = when (parameter("batchKind", 0)) {
        0 -> com.localdoc.scanner.structure.InvoiceReview.fields(raw)
        1 -> ReceiptExtractor.fields(raw)
        else -> ReceiptExtractor.custom(raw, parameter("template", ""))
    }
    private suspend fun spreadsheet(): FlowOutcome = step("spreadsheet", "生成XLSX，保留复核状态与原文", { it.files }) {
        val pages = parameter<List<ReviewPage>>("reviewPages", emptyList()).map { page -> if (page.fields.containsKey("发票号码")) page.copy(fields=com.localdoc.scanner.structure.InvoiceReview.revalidated(page.fields,page.rows)) else page }; require(pages.isNotEmpty()) { "没有可导出的识别结果" }
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        if (spec.request.tool.id != "table_xlsx") {
            val keys = pages.flatMap { it.fields.keys }.distinct()
            sheets += "汇总" to (listOf(listOf("来源", "页", "复核", "错误") + keys) + pages.map { page ->
                listOf(File(page.source).name, "${page.page + 1}", if (page.reviewed) "已确认" else "待复核", page.error) + keys.map { page.fields[it].orEmpty() }
            })
        }
        val tables = pages.filter { it.rows.isNotEmpty() }.mapIndexed { i, page -> TableSheet("明细${i+1}", page.rows, page.merges.orEmpty(), page.columnTypes.orEmpty()) }
        val tableSheets = if(parameter("combinePages", false)) listOf(TableLayout.combine(tables, parameter("skipHeaders", true))) else tables
        sheets += "原始识别" to (listOf(listOf("来源", "页", "原文分段", "错误")) + pages.flatMap { page ->
            page.raw.chunked(32000).ifEmpty { listOf("") }.map { listOf(File(page.source).name, "${page.page + 1}", it, page.error) }
        })
        val target = file("识别汇总.xlsx"); AtomicFiles.write(target) { SpreadsheetExport.writeTables(it, sheets.map { sheet -> TableSheet(sheet.first, sheet.second) } + tableSheets) }
        FlowOutcome(listOf("结果" to "已生成XLSX，${pages.count { !it.reviewed }}页尚未确认"), listOf(target))
    }
    private suspend fun barcode(): FlowOutcome {
        total = inputs.size + 1
        val found = inputs.flatMapIndexed { i, source -> step("barcode-v52:$i", "识别条码 ${i + 1}/${inputs.size}") {
            val bitmap = ImageIo.loadFromFile(source, 2000) ?: error("图片读取失败")
            try { BarcodeDecoder.decode(bitmap).map { com.localdoc.scanner.barcode.BarcodeRecord(it.text,it.barcodeFormat.name,System.currentTimeMillis()) } } finally { bitmap.recycle() }
        } }
        if (found.isEmpty()) return FlowOutcome(listOf("结果" to "没有识别到条码"), emptyList())
        val target = step("barcode-text:${version(found)}", "保存条码结果", { listOf(it) }) {
            file("条码.txt").also { AtomicFiles.text(it, found.joinToString("\n") {it.text}) }
        }
        found.forEach {com.localdoc.scanner.barcode.BarcodeHistory.add(context,it.text,it.format)}
        return FlowOutcome(found.mapIndexed { i, s -> "结果${i + 1}" to s.text }, listOf(target), copyText = found.first().text,barcodeRecords=found)
    }
    private data class ImageUnit(val files: List<File>, val notes: List<String>)
    private suspend fun editImages(): FlowOutcome {
        val order = parameter("imageOrder", inputs)
        require(order.size == inputs.size && order.toSet() == inputs.toSet()) { "图片顺序无效" }
        val mode = parameter("mode", 0)
        val rotation = parameter("rotation", 0)
        val filter = parameter("filter", ScanFilter.AUTO)
        val skew = parameter("autoDeskew", false)
        val ratios = parameter<Map<String, Float>>("bookSplits", emptyMap())
        val flatten = parameter("flattenBook", false)
        val reverse = parameter("bookRightFirst", false)
        val makePdf = parameter("imageBatchPdf", true)
        val targets = if(mode==2) order.map {it.absolutePath}.toSet() else com.localdoc.scanner.cv.ImageApplyScope.resolve(
            order.map {it.absolutePath},parameter("imageScope",0),parameter("imageSelected",0),parameter("imageTargets",emptyList<String>()))
        require(targets.isNotEmpty()) { "请至少选择一张要修改的图片" }
        total = (if (mode == 2) 1 else order.size) + if (makePdf) 1 else 0
        val outputs = mutableListOf<File>(); val notes = mutableListOf<String>(); val failures = mutableListOf<String>()
        val groups = if (mode == 2) { require(order.size == 2) { "证件拼版须选正反面两张图" }; listOf(order) } else order.map(::listOf)
        for ((index, group) in groups.withIndex()) try {
            val unit = step("image:$index", "处理第${index + 1}/${groups.size}组", { it.files }) {
                val owned = mutableListOf<Bitmap>(); val generated = mutableListOf<File>(); val messages = mutableListOf<String>()
                fun save(bitmap: Bitmap, suffix: String) { generated += file("图片${index + 1}_$suffix.jpg").also { check(ImageIo.saveJpeg(bitmap, it, 94)) } }
                try {
                    if (mode == 2) {
                        val sides = group.map { source ->
                            val bitmap = requireNotNull(ImageIo.loadFromFile(source, 2400)).also { owned += it }
                            ImageBatchProcessor.render(bitmap, rotation, skew, filter).bitmap.also { owned += it }
                        }
                        val sheet = DocumentLayouts.idCardSheet(sides[0], sides[1]).also { owned += it }; save(sheet, "证件双面")
                    } else if(group.first().absolutePath !in targets) {
                        val original=group.first()
                        val copy=file("图片${index+1}_保留原图.${original.extension}")
                        AtomicFiles.copy(original,copy);generated+=copy
                    } else {
                        val source = requireNotNull(ImageIo.loadFromFile(group.first(), 3200)).also { owned += it }
                        val pages = if (mode == 1) {
                            val split = DocumentLayouts.splitBookSpread(source, splitRatio = ratios[group.first().absolutePath] ?: BookGutter.estimate(source).ratio)
                            listOf(split.first to "左页", split.second to "右页").also { owned.addAll(it.map { pair -> pair.first }) }.let { if (reverse) it.reversed() else it }
                        } else listOf(source to "编辑")
                        for ((bitmap, suffix) in pages) {
                            val flat = if (mode == 1 && flatten) BookDewarp.flatten(bitmap).let { owned += it.bitmap; messages += it.note; it.bitmap } else bitmap
                            val result = ImageBatchProcessor.render(flat, rotation, skew, filter); owned += result.bitmap; messages += result.note
                            save(result.bitmap, suffix)
                        }
                    }
                    ImageUnit(generated, messages.filter { it.isNotBlank() })
                } catch (e: Exception) { generated.forEach { it.delete() }; throw e }
                finally { owned.distinct().forEach { it.recycle() } }
            }
            outputs += unit.files; notes += unit.notes
        } catch (e: Exception) { propagate(e); failures += "第${index + 1}组：${e.message}" }
        val files = outputs.toMutableList()
        if (makePdf && outputs.isNotEmpty() && failures.isEmpty()) try {
            files += pdfStep("images-pack:${version(outputs.map { ToolCheckpoints.hash(it) })}", "图片按序合并.pdf") {
                it.outputStream().use { stream -> PdfExporter.exportFiles(outputs, stream) }
            }
        } catch (e: Exception) { propagate(e); failures += "合并PDF失败：${e.message}" }
        return FlowOutcome(listOf("结果" to "生成${outputs.size}页") + failureSummary(failures) +
            if (notes.isEmpty()) emptyList() else listOf("处理说明" to notes.joinToString("\n")), files, outputs, failures = failures)
    }
    private fun failureSummary(failures: List<String>) = if (failures.isEmpty()) emptyList() else listOf("需处理" to failures.joinToString("\n"))
}
