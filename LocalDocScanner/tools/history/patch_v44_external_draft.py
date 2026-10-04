# Historical one-time patch; archived, intentionally disabled.
raise SystemExit("Historical patch only. Do not run against current sources.")

from pathlib import Path
import re
p=Path(__file__).resolve().parents[1]/'app/src/main/java/com/localdoc/scanner/external/ExternalFileScreen.kt'
s=p.read_text(encoding='utf-8')
s=s.replace('import com.localdoc.scanner.util.Share','import com.localdoc.scanner.util.Share\nimport com.localdoc.scanner.data.rememberToolState')
needle='    var localFile by remember(external)'
idx=s.index(needle)
s=s[:idx]+'''    val externalDraft = remember(external) {
        val token = MessageDigest.getInstance("SHA-256").digest(external.uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        com.localdoc.scanner.ui.ToolRequest(com.localdoc.scanner.model.ToolEntry("external", "外部文件", com.localdoc.scanner.model.FileKind.ANY), listOf(File(context.filesDir, token)), listOf(displayName))
    }
'''+s[idx:]
for name in ['localFile','office','textContent','status','retainWorkCopy','hashBeforeEngine','textDirty','openOfficeOnLoad']:
    pattern=rf'var {name} by remember\(external\) \{{ mutableStateOf(?:<([^\n]+?)>)?\(([^\n]+)\) \}}'
    def conv(m):
        typ,value=m.groups()
        return f'var {name} by rememberToolState{f"<{typ}>" if typ else ""}(externalDraft, "{name}") {{ {value} }}'
    s,n=re.subn(pattern,conv,s)
    assert n==1,name
s=s.replace('val edits = remember(external) { mutableStateMapOf<String, String>() }','var edits by rememberToolState<Map<String, String>>(externalDraft, "edits") { emptyMap() }')
s=s.replace('edits.clear()', 'edits = emptyMap()')
s=s.replace('edits: MutableMap<String, String>', 'edits: Map<String, String>')
# Workspace text updates are routed through a persistent immutable snapshot.
s=s.replace('                edits = edits,\n                status = status,','                edits = edits,\n                onEdit = { id, value -> edits = edits + (id to value) },\n                status = status,',1)
s=s.replace('    edits: Map<String, String>,\n', '    edits: Map<String, String>,\n    onEdit: (String, String) -> Unit,\n',1)
s=re.sub(r'edits\[unit.id\] = it','onEdit(unit.id, it)',s)
# Keep durable work copies through configuration destruction. Import only once per external URI session.
s=s.replace('if (!latestRetain) latestFile?.delete()', 'Unit')
s=s.replace('    LaunchedEffect(external) {\n        val loaded', '    LaunchedEffect(external) {\n        if (localFile?.isFile == true) { busy = false; return@LaunchedEffect }\n        val loaded')
s=s.replace('File(context.cacheDir, "external-open")', 'File(context.filesDir, "external-open")')
s=s.replace('            localFile = file\n            when', '            localFile = file\n            OutputHistoryStore.recordGenerated(context, file, resolvedMime)\n            when')
p.write_text(s,encoding='utf-8')
print('External work and quick-edit drafts now survive recreation.')
