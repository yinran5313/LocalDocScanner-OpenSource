from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/java/com/localdoc/scanner/ui/tools/ToolScreen.kt'
s=p.read_text(encoding='utf-8')
s=s.replace('import com.localdoc.scanner.data.FileStore','import com.localdoc.scanner.data.FileStore\nimport com.localdoc.scanner.data.rememberToolState')
# Persist only scalar/list configuration, output receipts and edit queues. Never passwords, bitmaps or busy flags.
pattern=r'var (\w+) by remember \{ mutable(?:Int|Float)?StateOf(?:<([^\n]+?)>)?\(([^\n]+)\) \}'
def convert(m):
    name,typ,value=m.groups()
    if name in {'password','confirm','busy','ocrBusy','signaturePreview','watermarkPreview'}: return m.group(0)
    return f'var {name} by rememberToolState{f"<{typ}>" if typ else ""}(request, "{name}") {{ {value} }}'
s=re.sub(pattern,convert,s)
s=s.replace('val formValues = remember(source) { mutableStateMapOf<String, String>() }','var formValues by rememberToolState<Map<String, String>>(request, "formValues") { emptyMap() }')
s=s.replace('formValues[field.name] = field.value','formValues = formValues + (field.name to field.value)')
s=s.replace('formValues[field.name] = it','formValues = formValues + (field.name to it)')
# A rotation keeps the job alive in the ViewModel. A process restart returns to configuration, never silently reruns it.
needle='    val saveSingle = rememberLauncherForActivityResult'
assert needle in s
s=s.replace(needle,'''    LaunchedEffect(request) {
        if (stage == 1 && !vm.isToolRunning) {
            stage = 0
            saveStatus = "上次处理已中断，配置和编辑清单已恢复，请确认后重新处理。"
        }
    }

'''+needle,1)
a=s.index('    fun start() {')
b=s.index('    fun toast',a)
part=s[a:b].replace('scope.launch {','vm.runTool {',1)
part=part.replace('runCatching { process(context) }','runCatching { process(context) }')
part=part.replace('.getOrElse { FlowOutcome(listOf("出错" to (it.message ?: "处理失败")), emptyList()) }','.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; FlowOutcome(listOf("出错" to (it.message ?: "处理失败")), emptyList()) }')
s=s[:a]+part+s[b:]
s=s.replace('if (busy) return\n        busy = true\n        stage = 1','if (busy || vm.isToolRunning) return\n        busy = true\n        stage = 1',1)
s=s.replace('Text("处理完成", style = MaterialTheme.typography.titleMedium)','Text(if (o == null || o.summary.any { it.first == "出错" }) "处理失败" else if (o.files.isEmpty()) "未生成文件" else "处理完成", style = MaterialTheme.typography.titleMedium)')
s=s.replace('modifier = Modifier.weight(1f)\n                        ) { Text("分享") }','enabled = !busy && !o?.files.isNullOrEmpty(),\n                            modifier = Modifier.weight(1f)\n                        ) { Text("分享") }',1)
s=s.replace('                config()','                if (saveStatus.isNotBlank()) Text(saveStatus)\n                config()',1)
s=s.replace('found += "${r.text}    [${r.barcodeFormat}]"','found += r.text')
s=s.replace('if (txt.startsWith("http", ignoreCase = true))','if (Uri.parse(txt).scheme?.lowercase() in setOf("http", "https") && !Uri.parse(txt).host.isNullOrBlank())')
p.write_text(s,encoding='utf-8')
p=root/'app/src/main/java/com/localdoc/scanner/ui/AppViewModel.kt'
s=p.read_text(encoding='utf-8')
s=s.replace('else toolRequest = ToolRequest(tool, files, names)','else { toolRequest = ToolRequest(tool, files, names); com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest) }')
s=s.replace('    fun closeTool() {\n        toolRequest = null','    fun closeTool() {\n        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, null)\n        toolRequest = null')
s=s.replace('            listOf(displayName)\n        )','            listOf(displayName)\n        )\n        com.localdoc.scanner.data.ToolDrafts.saveRequest(app, toolRequest)')
p.write_text(s,encoding='utf-8')
print('Configuration journals, job lifecycle and barcode/failed-output paths patched.')
