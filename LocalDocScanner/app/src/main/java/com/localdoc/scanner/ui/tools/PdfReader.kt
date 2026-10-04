package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.PdfReadSession
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PdfReader(file: File, modifier: Modifier = Modifier, initialPage: Int = 0,
    onWorkingCopyReady: ((File) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparingCopy by remember(file) { mutableStateOf(false) }
    var session by remember(file) { mutableStateOf<PdfReadSession?>(null) }
    var password by remember(file) { mutableStateOf("") }
    var openAttempt by remember(file) { mutableIntStateOf(0) }
    var needsPassword by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf("") }
    var page by rememberSaveable(file.absolutePath, initialPage) { mutableIntStateOf(initialPage) }
    var pageInput by rememberSaveable(file.absolutePath) { mutableStateOf("1") }
    var showText by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var query by rememberSaveable(file.absolutePath) { mutableStateOf("") }
    var hits by remember(file) { mutableStateOf<List<Int>>(emptyList()) }
    var searchStatus by remember(file) { mutableStateOf("") }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var text by remember(file) { mutableStateOf("") }
    var loading by remember(file) { mutableStateOf(true) }
    LaunchedEffect(file, openAttempt) {
        loading = true
        error = ""
        var opened: PdfReadSession? = null
        try {
        val result = withContext(Dispatchers.IO) { runCatching { PdfReadSession.open(file, password).also { opened = it } } }
        result.onSuccess {
            session?.close()
            session = it
            opened = null
            needsPassword = false
            page = page.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0))
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            needsPassword = it is InvalidPasswordException
            error = if (needsPassword) "请输入PDF密码；密码不会保存。" else "打开失败：${it.message}"
        }
        loading = false
        } finally { password = ""; opened?.close() }
    }
    val latestSession by rememberUpdatedState(session)
    val latestBitmap by rememberUpdatedState(bitmap)
    DisposableEffect(file) { onDispose { latestSession?.close(); latestBitmap?.recycle() } }
    LaunchedEffect(session, page, showText) {
        val current = session ?: return@LaunchedEffect
        loading = true
        var pendingBitmap: Bitmap? = null
        try {
        val result = withContext(Dispatchers.IO) { runCatching {
            if (showText) null to current.pageText(page) else current.render(page).also { pendingBitmap = it } to ""
        } }
        result.onSuccess { (image, value) ->
            bitmap?.takeIf { it !== image }?.recycle()
            bitmap = image
            pendingBitmap = null
            text = value
            error = ""
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = "页面读取失败：${it.message}" }
        pageInput = (page + 1).toString()
        loading = false
        } finally { pendingBitmap?.recycle() }
    }
    LaunchedEffect(session, query) {
        hits = emptyList()
        val current = session ?: return@LaunchedEffect
        if (query.isBlank()) { searchStatus = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        searchStatus = "搜索中…"
        val found = withContext(Dispatchers.IO) { runCatching {
            buildList {
                for (i in 0 until current.pageCount) {
                    ensureActive()
                    if (current.pageText(i).contains(query, ignoreCase = true)) add(i)
                }
            }
        } }
        found.onSuccess { hits = it; searchStatus = "匹配 ${it.size} 页" }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; searchStatus = "搜索失败：${it.message}" }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (needsPassword) {
            Text(error)
            OutlinedTextField(password, { password = it }, label = { Text("PDF密码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { openAttempt++ }, enabled = !loading) { Text("解锁阅读") }
        } else if (session != null) {
            if (onWorkingCopyReady != null && session!!.encrypted) {
                Text("继续处理会在App内部生成无密码工作副本，原文件保持加密；处理后的文件可重新设置PDF密码。", style = MaterialTheme.typography.bodySmall)
                if (session!!.signatureCount > 0) Text("此PDF含数字签名。创建工作副本会改变文件，原有签名可能失效；签名原件仍保留。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { needsPassword = true; error = "请输入另一个密码（例如拥有修改权限的密码）。" }, enabled = !preparingCopy) { Text("更换解锁密码") }
                Button(onClick = {
                    val current = session ?: return@Button
                    preparingCopy = true
                    scope.launch {
                        var pending: File? = null
                        try {
                            val out = withContext(Dispatchers.IO) {
                                val target = File(com.localdoc.scanner.data.FileStore.importDir(context), "unlocked_${java.util.UUID.randomUUID()}.pdf")
                                current.createWorkingCopy(target).also { pending = it }
                            }
                            onWorkingCopyReady(out)
                            pending = null
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = "无法继续处理：${e.message}"
                        } finally { pending?.delete(); preparingCopy = false }
                    }
                }, enabled = !preparingCopy && session!!.canCreateWorkingCopy) { Text(if (preparingCopy) "正在准备…" else "创建工作副本并继续") }
                if (!session!!.canCreateWorkingCopy) Text("当前密码只允许阅读；需要有修改权限的密码。", color = MaterialTheme.colorScheme.error)
            }
            val count = session!!.pageCount
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("上一页") }
                OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text("页 / $count") }, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val wanted = pageInput.toIntOrNull()
                    if (wanted != null && wanted in 1..count) page = wanted - 1 else error = "页码范围为1至$count"
                }) { Text("跳转") }
                TextButton(onClick = { page++ }, enabled = page + 1 < count && !loading) { Text("下一页") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showText, { showText = false }, label = { Text("页面") })
                FilterChip(showText, { showText = true }, label = { Text("文字 / 长按复制") })
            }
            OutlinedTextField(query, { query = it }, label = { Text("文内搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isNotBlank()) Row {
                Text(searchStatus, modifier = Modifier.weight(1f))
                TextButton(onClick = { hits.lastOrNull { it < page }?.let { page = it } ?: hits.lastOrNull()?.let { page = it } }, enabled = hits.isNotEmpty()) { Text("上个") }
                TextButton(onClick = { hits.firstOrNull { it > page }?.let { page = it } ?: hits.firstOrNull()?.let { page = it } }, enabled = hits.isNotEmpty()) { Text("下个") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (showText) SelectionContainer(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text(text.ifBlank { "此页没有文字层，可使用文字识别。" }, modifier = Modifier.padding(10.dp))
            } else bitmap?.let { ZoomableImage(it, "${file.absolutePath}:$page", Modifier.fillMaxWidth().weight(1f)) }
        } else Text(if (loading) "正在打开PDF…" else error)
    }
}
