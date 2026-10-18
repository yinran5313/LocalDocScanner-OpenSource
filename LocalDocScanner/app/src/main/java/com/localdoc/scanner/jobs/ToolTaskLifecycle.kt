package com.localdoc.scanner.jobs

internal object ToolTaskLifecycle {
    fun interrupted(task: ToolTaskStatus, observedAt: Long, work: List<Pair<String, Boolean>>): Boolean {
        if (task.state !in setOf(ToolTaskState.QUEUED, ToolTaskState.RUNNING) || task.updatedAt > observedAt) return false
        val finished = work.filter { task.workId == null || it.first == task.workId }.map { it.second }
        return if (finished.isEmpty()) observedAt - task.updatedAt > 30_000 else finished.all { it }
    }
}
