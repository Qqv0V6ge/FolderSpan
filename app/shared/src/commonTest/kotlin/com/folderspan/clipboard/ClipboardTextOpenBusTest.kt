package com.folderspan.clipboard

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardTextOpenBusTest {
    @Test
    fun retainsDroppedTextUntilOpenFlowReceivesIt() = runTest {
        assertFalse(ClipboardTextOpenBus.publish("  "))
        val text = "https://example.com/文件"
        assertTrue(ClipboardTextOpenBus.publish(text))
        assertEquals(ClipboardContent(texts = listOf(text)), ClipboardTextOpenBus.events.first())
    }
}
