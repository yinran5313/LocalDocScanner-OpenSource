package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import com.localdoc.scanner.R
import com.localdoc.scanner.ui.components.AppIcon
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
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = page.coerceAtLeast(0))
    var searchOpen by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpError by remember { mutableStateOf("") }
    var snapPages by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    fun goTo(target:Int) { page=target; scope.launch { listState.scrollToItem(target) } }
    LaunchedEffect(session, initialPage) { session?.let { listState.scrollToItem(page.coerceIn(0,(it.pageCount-1).coerceAtLeast(0))) } }
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
    DisposableEffect(file) { onDispose { latestSession?.close() } }
    LaunchedEffect(session, page, showText) {
        val current=session ?: return@LaunchedEffect
        if(!showText) return@LaunchedEffect
        loading=true
        try { text=withContext(Dispatchers.IO) { current.pageText(page) }; error="" }
        catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error="文字读取失败：${e.message}" }
        finally { loading=false }
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
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ pageInput=(page+1).toString(); jumpError=""; jumpOpen=true }) { Text("${page+1} / $count 页") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={ searchOpen=!searchOpen }) { AppIcon(R.drawable.ic_ui_search,"文内搜索") }
                Box {
                    IconButton(onClick={ menuOpen=true }) { AppIcon(R.drawable.ic_ui_more,"阅读选项") }
                    DropdownMenu(menuOpen,{ menuOpen=false }) {
                        DropdownMenuItem(text={ Text(if(showText) "返回页面" else "文字 · 长按复制") },onClick={ showText=!showText; menuOpen=false })
                        DropdownMenuItem(text={ Text(if(snapPages) "✓ 整页吸附" else "整页吸附") },onClick={ snapPages=!snapPages; menuOpen=false })
                    }
                }
            }
            if(searchOpen) {
                OutlinedTextField(query,{ query=it },placeholder={ Text("搜索文内文字") },singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={ IconButton(onClick={ query=""; searchOpen=false }) { AppIcon(R.drawable.ic_ui_close,"关闭搜索") } })
                if(query.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(searchStatus,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ (hits.lastOrNull { it<page } ?: hits.lastOrNull())?.let(::goTo) },enabled=hits.isNotEmpty()) { Text("上个") }
                    TextButton(onClick={ (hits.firstOrNull { it>page } ?: hits.firstOrNull())?.let(::goTo) },enabled=hits.isNotEmpty()) { Text("下个") }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (showText) SelectionContainer(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text(text.ifBlank { "此页没有文字层，可使用文字识别。" }, modifier = Modifier.padding(10.dp))
            } else PdfContinuousPages(session!!,listState,snapPages,{ page=it },Modifier.fillMaxWidth().weight(1f))
        } else Text(if (loading) "正在打开PDF…" else error)
    }
    if(jumpOpen) AlertDialog(onDismissRequest={ jumpOpen=false },title={ Text("跳转到页面") },text={
        OutlinedTextField(pageInput,{ pageInput=it.filter(Char::isDigit).take(6); jumpError="" },singleLine=true,label={ Text("1 至 ${session?.pageCount ?: 0}") },
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),
            isError=jumpError.isNotBlank(), supportingText={ if(jumpError.isNotBlank()) Text(jumpError) })
    },confirmButton={ TextButton(onClick={ val target=pageInput.toIntOrNull(); if(target!=null && target in 1..(session?.pageCount ?: 0)) { goTo(target-1); jumpOpen=false } else jumpError="请输入1至${session?.pageCount ?: 0}的页码" }) { Text("跳转") } },dismissButton={ TextButton(onClick={ jumpOpen=false }) { Text("取消") } })

}
