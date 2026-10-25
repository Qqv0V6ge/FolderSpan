package com.folderspan.ui.components.dialog

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskResidualDialogPriorityTest {
    @Test
    fun residualNoticeWaitsForCleanupAndCleanupErrors() {
        assertEquals(TaskCleanupDialogKind.Failure, taskCleanupDialogKind(true, true, true))
        assertEquals(TaskCleanupDialogKind.Failure, taskCleanupDialogKind(true, false, true))
        assertEquals(TaskCleanupDialogKind.Busy, taskCleanupDialogKind(false, true, true))
        assertEquals(TaskCleanupDialogKind.Residual, taskCleanupDialogKind(false, false, true))
        assertEquals(TaskCleanupDialogKind.None, taskCleanupDialogKind(false, false, false))
    }
}
