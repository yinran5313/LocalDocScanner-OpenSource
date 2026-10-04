# Historical one-time patch; archived, intentionally disabled.
raise SystemExit("Historical patch only. Do not run against current sources.")

from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/com/localdoc/scanner/ui/tools/ToolScreen.kt'
s=p.read_text(encoding='utf-8')
a=s.index('private fun OcrFlow(')
end=s.index('// 7. 条码识别',a)
part=s[a:end]
part=part.replace('var precise by rememberToolState(request, "precise") { 0 }','var precise by rememberToolState(request, "precise") { 1 }\n    var searchable by rememberToolState(request, "searchable") { true }')
part=part.replace('            if (engineAvailable) {','            Row { Switch(searchable, { searchable = it }); Text("同时生成可搜索PDF") }\n            if (engineAvailable) {',1)
b=part.index('    ) { ctx ->\n        val text =')
part=part[:b]+'''    ) { ctx ->
        val engine = PaddleOcrEngine(ctx)
        val pages = mutableListOf<com.localdoc.scanner.export.SearchablePdfPage>()
        val failures = mutableListOf<String>()
        val raw = StringBuilder()
        val temporary = File(ctx.cacheDir, "ocr-output-${System.nanoTime()}").apply { mkdirs() }
        try {
            request.files.forEach { source ->
                val count = if (source.extension.equals("pdf", true)) PdfTools.pageCount(source) else 1
                if (count == 0) failures += "${source.name}：无法读取PDF，请先解锁"
                for (index in 0 until count) {
                    try {
                        val bitmap = if (source.extension.equals("pdf", true)) PdfTools.renderPage(source, index, if (precise == 1) 3000 else 2000)
                            else ImageIo.loadFromFile(source, if (precise == 1) 3600 else 2400)
                        requireNotNull(bitmap) { "图片无法读取" }
                        try {
                            val recognized = engine.recognize(bitmap, precise == 1)
                            raw.append("【${source.name} 第${index + 1}页】\\n${recognized.text}\\n\\n")
                            if (recognized.text.isBlank()) failures += "${source.name} 第${index + 1}页：没有识别到文字"
                            if (searchable) {
                                val image = File(temporary, "${pages.size}.jpg")
                                check(ImageIo.saveJpeg(bitmap, image, 94)) { "无法保留PDF页面" }
                                pages += com.localdoc.scanner.export.SearchablePdfPage(image, recognized.text, recognized.boxes)
                            }
                        } finally { bitmap.recycle() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failures += "${source.name} 第${index + 1}页：${e.message}"
                    }
                }
            }
            val outputs = mutableListOf<File>()
            val text = raw.toString()
            if (text.isNotBlank()) {
                val out = File(FileStore.exportDir(ctx), "ocr_${System.currentTimeMillis()}.txt")
                out.writeText(text); outputs += out
            }
            if (searchable && pages.isNotEmpty()) {
                val pdf = File(FileStore.exportDir(ctx), "ocr_可搜索_${System.currentTimeMillis()}.pdf")
                val ok = pdf.outputStream().use { com.localdoc.scanner.export.SearchablePdfExporter.export(ctx, pages, it) }
                if (ok) outputs += pdf else { pdf.delete(); failures += "可搜索PDF生成失败，文字结果保留" }
            }
            FlowOutcome(listOf("字数" to "${text.length}", "PDF页面" to "${pages.size}") +
                if (failures.isNotEmpty()) listOf("需复核" to failures.joinToString("\\n")) else emptyList(), outputs)
        } finally {
            engine.close()
            temporary.listFiles()?.forEach { it.delete() }
            temporary.delete()
        }
    }
}

// ======================================================================
'''
s=s[:a]+part+s[end:]
p.write_text(s,encoding='utf-8')
print('OCR outputs TXT plus searchable PDF, with per-page failure reporting.')
