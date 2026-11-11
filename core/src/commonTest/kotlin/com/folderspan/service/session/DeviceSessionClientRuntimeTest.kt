package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.test.runSuspendTest
import com.folderspan.service.data.CopyPathRequest
import com.folderspan.service.operation.OperationRequestFeedback
import com.folderspan.service.operation.withOperationTransferControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import com.folderspan.service.data.toSerializableResult
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceSessionClientRuntimeTest {
    @Test fun cancellingAWaitingFileResetsOnlyThatStreamAndKeepsAnotherFileAndRpcAlive() = runSuspendTest {
        val clientToServer = Channel<ByteArray>(32)
        val serverToClient = Channel<ByteArray>(32)
        val scope = CoroutineScope(currentCoroutineContext())
        val plan = testPlan()
        val server = DeviceSessionPeer(DeviceSessionTransport(
            RuntimeTestByteChannel(clientToServer, serverToClient), plan, plan, isClient = false),
            plan, DeviceSessionEndpointRole.Server)
        val opened = MutableStateFlow<Map<String, Int>>(emptyMap())
        val reset = CompletableDeferred<Unit>()
        server.setRequestHandler { request -> DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK) }
        server.setStreamOpenHandler { streamId, open ->
            opened.value += open.path to streamId
            object : DeviceSessionIncomingStream {
                override suspend fun onData(chunk: ByteArray) = Unit
                override suspend fun onReset() { if (open.path == "/cancel") reset.complete(Unit) }
            }
        }
        server.start(scope)
        val runtime = DeviceSessionClientRuntimeLauncher.start(
            RuntimeTestByteChannel(serverToClient, clientToServer), scope,
            DeviceSessionClientAuthenticationContext("remote") { approvedResponse() }, sessionPlan = plan)
        runtime.clients.transferBudget.bind(4) {
            runtime.transferStatus.copy(sampledAtMillis = Clock.System.now().toEpochMilliseconds())
        }
        repeat(12) { runtime.clients.transferBudget.withPermit {} }
        val cancelled = MutableStateFlow(false)
        try {
            withTimeout(3_000) {
                val first = async {
                    withOperationTransferControl({ if (cancelled.value) throw CancellationException("user") }) {
                        runtime.clients.readStream("/cancel", 0, 1) {}
                    }
                }
                val second = async { runtime.clients.readStream("/other", 0, 1) {}.getOrThrow() }
                opened.first { it.size == 2 }
                cancelled.value = true
                first.join()
                assertTrue(first.isCancelled)
                withTimeout(500) { reset.await() }
                assertFalse(second.isCompleted)
                assertEquals(1, runtime.peer.connection.activeStreamCount.value)
                assertTrue(runtime.peer.rpc("metadata", byteArrayOf()).isSuccess)
                val id = opened.value.getValue("/other")
                server.connection.sendData(id, byteArrayOf(1))
                server.connection.sendTrailer(id)
                assertTrue(second.await())
            }
        } finally { runtime.close(); server.close() }
    }

    @Test fun independentFileCallsShareTheNegotiatedBudgetWhileControlRpcKeepsWorking() = runSuspendTest {
        val clientToServer = Channel<ByteArray>(32)
        val serverToClient = Channel<ByteArray>(32)
        val scope = CoroutineScope(currentCoroutineContext())
        val plan = testPlan()
        val server = DeviceSessionPeer(DeviceSessionTransport(
            RuntimeTestByteChannel(clientToServer, serverToClient), plan, plan, isClient = false),
            plan, DeviceSessionEndpointRole.Server)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var completed = 0
        server.setRequestHandler { request ->
            if (request.method == DEVICE_SESSION_RPC_COPY_PATH) {
                active++
                peak = maxOf(peak, active)
                try { entered.complete(Unit); release.await(); delay(5); completed++ }
                finally { active-- }
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK,
                    ProtoBufCodec.encode(Result.success(ProtoBufCodec.decode<CopyPathRequest>(request.payload).srcPath != "/bad").toSerializableResult()))
            } else DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK)
        }
        server.start(scope)
        val runtime = DeviceSessionClientRuntimeLauncher.start(
            RuntimeTestByteChannel(serverToClient, clientToServer), scope,
            DeviceSessionClientAuthenticationContext("remote") { approvedResponse().copy(maxFileStreams = 1) }, sessionPlan = plan)
        try {
            withTimeout(3_000) {
                val copies = List(9) { index -> async {
                    runtime.clients.copyPath("/source-$index", "/target-$index", null, "copy-$index").getOrThrow()
                } }
                entered.await()
                assertTrue(runtime.peer.rpc("metadata", byteArrayOf()).isSuccess)
                assertEquals(1, peak)
                assertFalse(copies.all { it.isCompleted })
                release.complete(Unit)
                assertTrue(copies.awaitAll().all { it })
                assertEquals(9, completed)
                assertEquals(1, peak)
                val feedback = OperationRequestFeedback { 0 }
                withContext(feedback) {
                    assertFalse(runtime.clients.copyPath("/bad", "/bad-target", null, "bad-copy").getOrThrow())
                }
                assertTrue(feedback.failed, "A captured false RPC result must reach the shared limiter")
            }
        } finally { release.complete(Unit); runtime.close(); server.close() }
    }

    @Test fun carrierWithoutReceiptTimingDoesNotSubscribeEvenWhenThePeerAdvertisesFeedback() = runSuspendTest {
        val raw = RuntimeTestByteChannel()
        val plan = testPlan()
        val native = DeviceSessionTransport(raw, plan, plan, true)
        val channel = object : DeviceSessionPlatformChannel {
            override fun frameTransport(sendPlan: DeviceSessionWindowPlan, receivePlan: DeviceSessionWindowPlan,
                isClient: Boolean, onPing: (suspend (ByteArray) -> Unit)?) = object : DeviceSessionPlatformTransport {
                override val connection = object : DeviceSessionFrameConnection by native.connection {
                    override val supportsFeedbackTiming = false
                }
                override fun start(scope: CoroutineScope) = native.start(scope)
            }
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("framed carrier only")
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { error("framed carrier only") }
            override suspend fun flush() = Unit
            override fun close() { raw.close() }
        }
        val scope = CoroutineScope(currentCoroutineContext())
        val runtime = withTimeout(500) {
            DeviceSessionClientRuntimeLauncher.start(channel, scope,
                DeviceSessionClientAuthenticationContext("remote") { approvedResponse().copy(supportsTransferFeedback = true) },
                sessionPlan = plan)
        }
        try {
            assertEquals(0L, runtime.clients.transferStatus().sampledAtMillis)
            assertEquals(1, runtime.clients.transferStatus().recommendedParallelRequests)
        } finally { runtime.close() }
    }

    @Test fun negotiatedFeedbackUsesTheAuthenticatedControlChannelInBothDirections() = runSuspendTest {
        val clientToServer = Channel<ByteArray>(32)
        val serverToClient = Channel<ByteArray>(32)
        val clientChannel = RuntimeTestByteChannel(serverToClient, clientToServer)
        val serverChannel = RuntimeTestByteChannel(clientToServer, serverToClient)
        val scope = CoroutineScope(currentCoroutineContext())
        val plan = testPlan()
        val server = DeviceSessionPeer(DeviceSessionTransport(serverChannel, plan, plan, isClient = false),
            plan, DeviceSessionEndpointRole.Server)
        server.start(scope)
        val feedback = DeviceSessionTransferFeedback(scope,
            plan.toTransferStatus(4, DEVICE_SESSION_MAX_PAYLOAD_BYTES, 1), server.transferActivity(), server::rpc)
        server.setRequestHandler { request -> feedback.handle(request)
            ?: DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_NOT_FOUND) }
        var runtime: DeviceSessionClientRuntime? = null
        try {
            val connected = DeviceSessionClientRuntimeLauncher.start(clientChannel, scope,
                DeviceSessionClientAuthenticationContext("remote") { approvedResponse().copy(supportsTransferFeedback = true) },
                sessionPlan = plan)
            runtime = connected
            withTimeout(2_000) {
                while (connected.clients.transferStatus().sampledAtMillis == 0L || feedback.current().sampledAtMillis == 0L) delay(1)
            }
            assertEquals(4, connected.clients.transferStatus().maxParallelRequests)
            val sampledAt = connected.clients.transferStatus().sampledAtMillis
            delay(5)
            assertEquals(sampledAt, connected.clients.transferStatus().sampledAtMillis)
        } finally { runtime?.close(); feedback.close(); server.close() }
    }

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
        assertEquals(0L, runtime.clients.transferStatus().sampledAtMillis)
        assertEquals(1, runtime.clients.transferStatus().recommendedParallelRequests)
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

    private class RuntimeTestByteChannel(
        private val incoming: Channel<ByteArray> = Channel(capacity = 1),
        private val outgoing: Channel<ByteArray>? = null,
    ) : DeviceSessionByteChannel {
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

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            outgoing?.send(buffer.copyOfRange(offset, offset + length))
        }

        override suspend fun flush() = Unit

        override fun close() {
            if (locallyClosed) return
            locallyClosed = true
            closeCount += 1
            incoming.close()
            outgoing?.close()
        }

        fun closeIncoming() {
            incoming.close()
        }
    }
}
