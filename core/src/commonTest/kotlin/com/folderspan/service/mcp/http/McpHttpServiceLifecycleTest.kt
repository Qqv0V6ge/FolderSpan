package com.folderspan.service.mcp.http

import com.folderspan.service.mcp.McpServerSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHttpServiceLifecycleTest {
    @Test
    fun foregroundBackgroundCallbacksAreSerializedAndUseLatestSettings() = runTest {
        val service = RecordingMcpService()
        var settings = McpServerSettings(enabled = true, port = 52137)
        val lifecycle = McpHttpServiceLifecycle(service) { settings }

        assertTrue(lifecycle.enterForeground().running)
        settings = settings.copy(port = 13041)
        assertTrue(lifecycle.enterForeground().running)
        assertEquals(13041, service.status().port)
        assertFalse(lifecycle.enterBackground().running)

        listOf(
            async { lifecycle.enterForeground() },
            async { lifecycle.enterBackground() },
            async { lifecycle.enterForeground() },
        ).awaitAll()
        assertEquals(1, service.maxConcurrentCalls)
    }
}

private class RecordingMcpService : McpHttpServiceInterface {
    private var current = McpHttpServiceStatus(supported = true, running = false)
    private var activeCalls = 0
    var maxConcurrentCalls = 0
        private set

    override suspend fun start(settings: McpServerSettings): McpHttpServiceStatus = record {
        current = McpHttpServiceStatus(supported = true, running = settings.enabled, port = settings.port)
        current
    }

    override suspend fun stop(): McpHttpServiceStatus = record {
        current = McpHttpServiceStatus(supported = true, running = false)
        current
    }

    override suspend fun restart(settings: McpServerSettings): McpHttpServiceStatus = record {
        current = McpHttpServiceStatus(supported = true, running = settings.enabled, port = settings.port)
        current
    }

    override fun status(): McpHttpServiceStatus = current

    private suspend inline fun <T> record(block: () -> T): T {
        activeCalls++
        maxConcurrentCalls = maxOf(maxConcurrentCalls, activeCalls)
        return try {
            delay(1)
            block()
        } finally {
            activeCalls--
        }
    }
}
