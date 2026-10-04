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
        crossinline files: (T) -> List<File> = { emptyList() }, crossinline run: suspend () -> T): T {
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
        completed++
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
    } else when (spec.request.tool.id) {
        "images_to_pdf" -> imagesToPdf()
        "pdf_merge" -> {
            val order = parameter("order", inputs)
            require(order.size == inputs.size && order.toSet() == inputs.toSet()) { "合并顺序无效，请重新选择文件" }
            val result = pdfStep("merge", "合并.pdf") { PdfTools.merge(order, it) }
            FlowOutcome(listOf("输入" to "${order.size}份"), listOf(result))
        }
        "pdf_split" -> split()
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
        val quality = intArrayOf(2400, 1600, 1100)[parameter("quality", 1).coerceIn(0, 2)]
        val size = if (parameter("pageSize", 0) == 0) PdfExporter.PageSize.A4 else PdfExporter.PageSize.FIT_IMAGE
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
        total = count
        val dpi = intArrayOf(200, 150, 110)[parameter("dpi", 1).coerceIn(0, 2)]
        val failures = mutableListOf<String>()
        val images = mutableListOf<File>()
        for (index in 0 until count) try {
            images += step("render:$index", "导出第${index + 1}/$count 页", { listOf(it) }) {
                val bitmap = PdfTools.renderPageAtDpi(first, index, dpi) ?: error("无法读取页面")
                try { file("第${index + 1}页.jpg").also { check(ImageIo.saveJpeg(bitmap, it, 94)) } } finally { bitmap.recycle() }
            }
        } catch (e: Exception) { propagate(e); failures += "第${index + 1}页：${e.message}" }
        return FlowOutcome(listOf("导出页数" to "${images.size}/$count") + failureSummary(failures), images, images, failures = failures)
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
                when (edit.operation) {
                    0 -> {
                        val image = edit.watermarkUri?.let { ImageIo.loadFromUri(context, it, 1600) }
                        try { PdfOfficeTools.decorate(context, previous, target, edit.watermark, edit.header, edit.footer,
                            edit.addPageNumbers, edit.opacity, image, edit.decoration ?: PdfDecorationOptions()) }
                        finally { image?.recycle() }
                    }
                    1 -> edit.text.isNotBlank() && PdfOfficeTools.addText(context, previous, target, edit.pageIndex, edit.text, edit.x, edit.y, 13f)
                    2 -> PdfOfficeTools.addMarkup(previous, target, edit.pageIndex, edit.markup, edit.x, edit.y, edit.width, edit.height)
                    3 -> edit.text.isNotBlank() && PdfOfficeTools.addNote(previous, target, edit.pageIndex, edit.text, edit.x, edit.y)
                    4 -> edit.strokes.any { it.size >= 2 } && PdfOfficeTools.drawInk(previous, target, edit.pageIndex, edit.strokes, edit.x, edit.y, edit.width, edit.height)
                    5 -> {
                        val image = edit.signatureUri?.let { ImageIo.loadFromUri(context, it, 1600) } ?: error("签名图片不可读取")
                        try { PdfOfficeTools.addSignatureImage(previous, target, edit.pageIndex, image, edit.x, edit.y, edit.width, edit.height) }
                        finally { image.recycle() }
                    }
                    6 -> edit.formValues.isNotEmpty() && PdfOfficeTools.fillForm(context, previous, target, edit.formValues)
                    8 -> edit.annotationChange?.let { PdfAnnotationEditor.apply(previous, target, it) } ?: false
                    9 -> edit.textChange?.let { PdfOriginalTextEditor.apply(previous, target, it) } ?: false
                    else -> PdfTools.redactPermanent(previous, target, edit.pageIndex, edit.x, edit.y, edit.width, edit.height)
                }
            }
        }
        return FlowOutcome(listOf("操作" to edits.joinToString("、") { it.label }, "结果" to "已生成编辑副本"), listOf(input))
    }

    private data class RecognizedPage(val source: String, val page: Int, val image: File?, val text: String, val boxes: List<OcrTextBox>)
    private suspend fun recognize(): FlowOutcome {
        val structured = spec.request.tool.id in setOf("batch_extract", "table_xlsx")
        val card = spec.request.tool.id == "card"
        val medium = if (structured) parameter("batchMedium", true) else if (card) parameter("precise", true) else parameter("precise", 1) == 1
        val searchable = !structured && !card && parameter("searchable", true)
        val counts = inputs.map { if (it.extension.equals("pdf", true)) PdfTools.pageCount(it) else 1 }
        total = counts.sum() + if (structured || card) 0 else if (searchable) 2 else 1
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
                                val result = engine.recognize(bitmap, medium)
                                require(result.text.isNotBlank()) { "未识别到文字，请调整图片或录入" }
                                val image = if (searchable) file("OCR_${fileIndex}_${index}.jpg").also { check(ImageIo.saveJpeg(bitmap, it, 94)) } else null
                                RecognizedPage(source.absolutePath, index, image, result.text, result.boxes)
                            } finally { bitmap.recycle() }
                        }
                        pages += value
                        if (structured) reviews += ReviewPage(value.source, index, value.text,
                            if (spec.request.tool.id == "table_xlsx") emptyMap() else fields(value.text),
                            TableRecovery.fromBoxes(value.boxes).ifEmpty { TableRecovery.fromText(value.text) })
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
            copyText = text, failures = failures)
    }
    private fun fields(raw: String) = when (parameter("batchKind", 0)) {
        0 -> StructureExtractor.extract(StructureExtractor.Kind.INVOICE, raw).fields.associate { it.label to it.value }
        1 -> ReceiptExtractor.fields(raw)
        else -> ReceiptExtractor.custom(raw, parameter("template", ""))
    }
    private suspend fun spreadsheet(): FlowOutcome = step("spreadsheet", "生成XLSX，保留复核状态与原文", { it.files }) {
        val pages = parameter<List<ReviewPage>>("reviewPages", emptyList()); require(pages.isNotEmpty()) { "没有可导出的识别结果" }
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        if (spec.request.tool.id != "table_xlsx") {
            val keys = pages.flatMap { it.fields.keys }.distinct()
            sheets += "汇总" to (listOf(listOf("来源", "页", "复核", "错误") + keys) + pages.map { page ->
                listOf(File(page.source).name, "${page.page + 1}", if (page.reviewed) "已确认" else "待复核", page.error) + keys.map { page.fields[it].orEmpty() }
            })
        }
        pages.forEachIndexed { i, page -> if (page.rows.isNotEmpty()) sheets += "明细${i + 1}" to page.rows }
        sheets += "原始识别" to (listOf(listOf("来源", "页", "原文分段", "错误")) + pages.flatMap { page ->
            page.raw.chunked(32000).ifEmpty { listOf("") }.map { listOf(File(page.source).name, "${page.page + 1}", it, page.error) }
        })
        val target = file("识别汇总.xlsx"); AtomicFiles.write(target) { SpreadsheetExport.write(it, sheets) }
        FlowOutcome(listOf("结果" to "已生成XLSX，${pages.count { !it.reviewed }}页尚未确认"), listOf(target))
    }
    private suspend fun barcode(): FlowOutcome {
        total = inputs.size + 1
        val found = inputs.flatMapIndexed { i, source -> step("barcode:$i", "识别条码 ${i + 1}/${inputs.size}") {
            val bitmap = ImageIo.loadFromFile(source, 2000) ?: error("图片读取失败")
            try { BarcodeDecoder.decode(bitmap).map { it.text } } finally { bitmap.recycle() }
        } }
        if (found.isEmpty()) return FlowOutcome(listOf("结果" to "没有识别到条码"), emptyList())
        val target = step("barcode-text:${version(found)}", "保存条码结果", { listOf(it) }) {
            file("条码.txt").also { AtomicFiles.text(it, found.joinToString("\n")) }
        }
        return FlowOutcome(found.mapIndexed { i, s -> "结果${i + 1}" to s }, listOf(target), copyText = found.first())
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
