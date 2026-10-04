package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ToolTaskPanel(task: ToolTaskStatus, onPause: () -> Unit, onResume: () -> Unit) {
    val running = task.state in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING)
    val label = when (task.state) {
        ToolTaskState.QUEUED -> "等待 / 自动续跑"
        ToolTaskState.RUNNING -> "后台处理中"
        ToolTaskState.PAUSED -> "已暂停"
        ToolTaskState.WAITING_PASSWORD -> "等待重新输入密码"
        ToolTaskState.FAILED -> "未完成"
        ToolTaskState.PARTIAL -> "部分完成"
        ToolTaskState.SUCCEEDED -> "已完成"
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$label · ${task.completed}/${task.total}步骤")
            Text(task.label, style = MaterialTheme.typography.bodySmall)
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (task.error.isNotBlank()) Text(task.error, color = MaterialTheme.colorScheme.error)
            Text("续跑使用原提交参数，校验并复用成功项；单次操作重试当前步骤。", style = MaterialTheme.typography.bodySmall)
            if (running) TextButton(onClick = onPause) { Text("暂停并保留进度") }
            else if (task.state != ToolTaskState.SUCCEEDED) TextButton(onClick = onResume) { Text("续跑 / 重试失败项") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolTasksScreen(onBack: () -> Unit, onOpen: (ToolTaskSpec) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by remember { ToolTasks.watch(context) }.collectAsState(emptyList())
    var error by remember { mutableStateOf("") }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(tasks.map { it.id }) {
        labels = withContext(Dispatchers.IO) { tasks.associate { it.id to runCatching { ToolTasks.spec(context, it.id).request.tool.label }.getOrDefault("工具任务") } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("工具任务与续跑") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("返回首页不会取消任务。点击任务可查看结果、保存、分享，或续跑未完成项。") }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            if (tasks.isEmpty()) item { Text("暂无工具任务") }
            items(tasks, key = { it.id }) { task ->
                Text(labels[task.id].orEmpty(), style = MaterialTheme.typography.titleMedium)
                ToolTaskPanel(task,
                    onPause = { scope.launch { runCatching { ToolTasks.pause(context, task.id) }.onFailure { error = it.message.orEmpty() } } },
                    onResume = { scope.launch {
                        try {
                            val spec = withContext(Dispatchers.IO) { ToolTasks.spec(context, task.id) }
                            if (spec.needsPassword) { ToolTasks.select(context, spec); onOpen(spec) }
                            else ToolTasks.resume(context, task.id)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message.orEmpty() }
                    } })
                TextButton(onClick = { scope.launch {
                    try { val spec = withContext(Dispatchers.IO) { ToolTasks.spec(context, task.id) }; ToolTasks.select(context, spec); onOpen(spec) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message.orEmpty() }
                } }) { Text("打开此任务") }
            }
        }
    }
}
