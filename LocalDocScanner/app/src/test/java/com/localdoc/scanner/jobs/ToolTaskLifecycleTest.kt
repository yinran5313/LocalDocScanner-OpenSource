package com.localdoc.scanner.jobs

import org.junit.Assert.*
import org.junit.Test

class ToolTaskLifecycleTest {
    @Test fun cancelledOldWorkCannotPauseNewlyQueuedResume() {
        val resumed = ToolTaskStatus("job", workId = "new", updatedAt = 1000)
        assertFalse(ToolTaskLifecycle.interrupted(resumed, 1100, listOf("old" to true)))
        assertFalse(ToolTaskLifecycle.interrupted(resumed, 1100, listOf("old" to true, "new" to false)))
        assertTrue(ToolTaskLifecycle.interrupted(resumed, 1100, listOf("old" to true, "new" to true)))
    }
    @Test fun snapshotTakenBeforeResumeMustNeverReconcileNewState() {
        val resumed = ToolTaskStatus("job", workId = "new", updatedAt = 5000)
        assertFalse(ToolTaskLifecycle.interrupted(resumed, 4999, listOf("new" to true)))
    }
    @Test fun genuineOrphanRecoversAfterGraceAndPauseIsStable() {
        val queued = ToolTaskStatus("job", workId = "new", updatedAt = 1000)
        assertFalse(ToolTaskLifecycle.interrupted(queued, 31000, emptyList()))
        assertTrue(ToolTaskLifecycle.interrupted(queued, 31001, emptyList()))
        assertFalse(ToolTaskLifecycle.interrupted(queued.copy(state = ToolTaskState.PAUSED), 50000, listOf("new" to true)))
        assertTrue(ToolTaskLifecycle.interrupted(queued.copy(workId = null), 1100, listOf("legacy" to true)))
    }
}
