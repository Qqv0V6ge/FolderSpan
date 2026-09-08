package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceSessionClientRuntimeTest {
    @Test
    fun launcherBuildsCommonClientsFromAuthenticatedByteChannel() = runSuspendTest {
        val channel = RuntimeTestByteChannel()
        val scope = CoroutineScope(currentCoroutineContext())

        val runtime = DeviceSessionClientRuntimeLauncher.start(
            channel = channel,
            scope = scope,
            authentication = approvedAuthentication(),
            sessionPlan = testPlan(),
        )

        assertEquals(DeviceConnectType.APPROVED, runtime.connectResponse.connection.connectType)
        assertEquals(4, runtime.transferStatus.maxParallelRequests)
        assertTrue(runtime.clients.archiveCapabilities().codecs.isNotEmpty())
        runtime.close()
        withTimeout(500L) { runtime.sessionJob.join() }
        assertEquals(1, channel.closeCount)
    }

    @Test
    fun authenticationFailureClosesChannelAndSession() = runSuspendTest {
        val channel = RuntimeTestByteChannel()
        val scope = CoroutineScope(currentCoroutineContext())

        assertFailsWith<IllegalStateException> {
            DeviceSessionClientRuntimeLauncher.start(
                channel = channel,
                scope = scope,
                authentication = DeviceSessionClientAuthenticationContext("remote") {
                    throw IllegalStateException("authentication failed")
                },
                sessionPlan = testPlan(),
            )
        }

        assertEquals(1, channel.closeCount)
    }

    @Test
    fun carrierFailureDuringAuthenticationReleasesRuntimeResources() = runSuspendTest {
        val channel = RuntimeTestByteChannel().apply { closeIncoming() }
        val scope = CoroutineScope(currentCoroutineContext())

        assertFailsWith<DeviceSessionClosedException> {
            withTimeout(500L) {
                DeviceSessionClientRuntimeLauncher.start(
                    channel = channel,
                    scope = scope,
                    authentication = DeviceSessionClientAuthenticationContext("remote") { peer ->
                        peer.ping(byteArrayOf(1))
                        approvedResponse()
                    },
                    sessionPlan = testPlan(),
                )
            }
        }

        assertEquals(1, channel.closeCount)
    }

    private fun approvedAuthentication() = DeviceSessionClientAuthenticationContext("remote") {
        approvedResponse()
    }

    private fun approvedResponse() = DeviceSessionConnectResponse(
        connection = DeviceConnectResponse(
            connectType = DeviceConnectType.APPROVED,
            token = "session-token",
            authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
        ),
        maxFileStreams = 4,
        recommendedChunkBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        archiveCapabilities = FolderSpanArchiveStreamCapabilities.local(),
    )

    private fun testPlan() = DeviceSessionWindowPlan(
        sessionWindowBytes = 2 * 1024 * 1024,
        streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        maxFileStreams = 4,
    )

    private class RuntimeTestByteChannel : DeviceSessionByteChannel {
        private val incoming = Channel<ByteArray>(capacity = 1)
        var closeCount = 0
            private set
        private var locallyClosed = false
        private var current = ByteArray(0)
        private var currentOffset = 0

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (currentOffset >= current.size) {
                current = incoming.receiveCatching().getOrNull() ?: return -1
                currentOffset = 0
            }
            val count = minOf(length, current.size - currentOffset)
            current.copyInto(
                destination = buffer,
                destinationOffset = offset,
                startIndex = currentOffset,
                endIndex = currentOffset + count,
            )
            currentOffset += count
            return count
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

        override suspend fun flush() = Unit

        override fun close() {
            if (locallyClosed) return
            locallyClosed = true
            closeCount += 1
            incoming.close()
        }

        fun closeIncoming() {
            incoming.close()
        }
    }
}
