package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SyncDeviceConnectionTest {
    private val device = SocketDevice("source-device", "Source", "/", type = DeviceType.JVM)

    @Test
    fun connectedDeviceDoesNotReconnectAndMissingDeviceDoesNotStartConnection() = runBlocking {
        assertTrue(connectSyncDeviceEndpoint({ true }, { error("discovery unnecessary") }, { error("reconnect") }).getOrThrow())
        assertTrue(connectSyncDeviceEndpoint({ false }, { null }, { error("missing device") }).isFailure)
    }

    @Test
    fun discoveredDeviceConnectsAndWaitsUntilItIsAvailable() = runBlocking {
        var connected = false
        val requested = CompletableDeferred<SocketDevice>()
        val result = async {
            connectSyncDeviceEndpoint({ connected }, { device }, { requested.complete(it) }, timeoutMillis = 1_000)
        }
        assertEquals(device, requested.await())
        delay(20)
        assertFalse(result.isCompleted)
        connected = true
        assertTrue(result.await().getOrThrow())
    }

    @Test
    fun failedConnectionReturnsItsCauseAndWaitingHasABound() = runBlocking {
        val failure = IllegalStateException("connection rejected")
        val actual = connectSyncDeviceEndpoint({ false }, { device }, { throw failure }).exceptionOrNull()
        assertEquals(failure.message, assertIs<IllegalStateException>(actual).message)
        assertFalse(connectSyncDeviceEndpoint({ false }, { device }, { awaitCancellation() }, timeoutMillis = 10).getOrThrow())
        assertFalse(connectSyncDeviceEndpoint({ false }, { device }, {}, timeoutMillis = 10).getOrThrow())
    }
}
