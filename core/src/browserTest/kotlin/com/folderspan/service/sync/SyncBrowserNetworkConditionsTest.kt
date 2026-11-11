@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.folderspan.service.sync

import kotlinx.browser.window
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncBrowserNetworkConditionsTest {
    @Test fun browserEventsUpdateRevisionAndClosingTheMonitorRemovesItsListener() = runTest {
        val monitor = SyncConditionsMonitor(backgroundScope)
        try {
            runCurrent()
            assertFalse(monitor.state.value.supportsConditions)
            val original = monitor.state.value.networkRevision
            fun onlineEvent() {
                val event = window.document.createEvent("Event")
                event.initEvent("online", false, false)
                window.dispatchEvent(event)
            }
            onlineEvent(); runCurrent()
            assertTrue(monitor.state.value.networkRevision > original)
            val beforeClose = monitor.state.value.networkRevision
            monitor.close(); runCurrent()
            onlineEvent(); runCurrent()
            assertEquals(beforeClose, monitor.state.value.networkRevision)
        } finally { monitor.close() }
    }
}
