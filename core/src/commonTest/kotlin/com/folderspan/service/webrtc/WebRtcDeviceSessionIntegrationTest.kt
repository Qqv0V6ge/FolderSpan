package com.folderspan.service.webrtc

import com.folderspan.service.session.DEVICE_SESSION_MAX_PAYLOAD_BYTES
import com.folderspan.service.session.DEVICE_SESSION_RPC_OK
import com.folderspan.service.session.DeviceSessionControlResponse
import com.folderspan.service.session.DeviceSessionEndpointRole
import com.folderspan.service.session.DeviceSessionIncomingStream
import com.folderspan.service.session.DeviceSessionPeer
import com.folderspan.service.session.DeviceSessionTransport
import com.folderspan.service.session.DeviceSessionWindowPlan
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcDataChannelState
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRtcDeviceSessionIntegrationTest {
    @Test
    fun concurrentFileStreamsAndRpcShareOneSessionChannel() = runSuspendTest {
        WebRtcSessionPeerPair().use { client, server ->
            val receivedBytes = mutableMapOf<String, Int>()
            val receivedMutex = Mutex()
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            server.setStreamOpenHandler { streamId, open ->
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        receivedMutex.withLock {
                            receivedBytes[open.path] = receivedBytes.getOrElse(open.path) { 0 } + chunk.size
                        }
                    }

                    override suspend fun onTrailer(payload: ByteArray) {
                        server.connection.sendTrailer(streamId)
                    }
                }
            }

            val streamSize = DEVICE_SESSION_MAX_PAYLOAD_BYTES * 3
            coroutineScope {
                val streams = (0 until 4).map { index ->
                    async {
                        client.openWriteStream(
                            path = "/stream-$index.bin",
                            fileSize = streamSize.toLong(),
                            startOffset = 0L,
                            data = flow {
                                repeat(3) { chunkIndex ->
                                    emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES) { (index + chunkIndex).toByte() })
                                    yield()
                                }
                            },
                        )
                    }
                }
                val rpcCalls = (0 until 20).map { index ->
                    async {
                        val payload = "rpc-$index".encodeToByteArray()
                        val response = client.rpc("Echo", payload)
                        assertEquals(DEVICE_SESSION_RPC_OK, response.status)
                        assertContentEquals(payload, response.payload)
                    }
                }
                (streams + rpcCalls).forEach { it.await() }
            }

            assertEquals(4, receivedBytes.size)
            assertTrue(receivedBytes.values.all { it == streamSize })
        }
    }

    @Test
    fun cancellingOneStreamKeepsSessionAndOtherStreamsUsable() = runSuspendTest {
        WebRtcSessionPeerPair().use { client, server ->
            val firstChunkReceived = CompletableDeferred<Unit>()
            val resetReceived = CompletableDeferred<Unit>()
            server.setRequestHandler { request ->
                DeviceSessionControlResponse(request.requestId, DEVICE_SESSION_RPC_OK, request.payload)
            }
            server.setStreamOpenHandler { streamId, open ->
                object : DeviceSessionIncomingStream {
                    override suspend fun onData(chunk: ByteArray) {
                        if (open.path == "/cancelled.bin") firstChunkReceived.complete(Unit)
                    }

                    override suspend fun onTrailer(payload: ByteArray) {
                        server.connection.sendTrailer(streamId)
                    }

                    override suspend fun onReset() {
                        if (open.path == "/cancelled.bin") resetReceived.complete(Unit)
                    }
                }
            }

            coroutineScope {
                val cancelled = async {
                    client.openWriteStream(
                        path = "/cancelled.bin",
                        fileSize = (DEVICE_SESSION_MAX_PAYLOAD_BYTES * 2L),
                        startOffset = 0L,
                        data = flow {
                            emit(ByteArray(DEVICE_SESSION_MAX_PAYLOAD_BYTES))
                            awaitCancellation()
                        },
                    )
                }
                firstChunkReceived.await()
                cancelled.cancelAndJoin()
                withTimeout(1_000L) { resetReceived.await() }

                val payload = "session-still-open".encodeToByteArray()
                assertContentEquals(payload, client.rpc("Echo", payload).payload)
                client.openWriteStream(
                    path = "/completed.bin",
                    fileSize = 3L,
                    startOffset = 0L,
                    data = flowOf(byteArrayOf(1, 2, 3)),
                )
            }
        }
    }
}

private class WebRtcSessionPeerPair {
    private val clientDataChannel = CrossWiredSessionDataChannel()
    private val serverDataChannel = CrossWiredSessionDataChannel()
    private val plan = DeviceSessionWindowPlan(
        sessionWindowBytes = 2 * 1024 * 1024,
        streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
        maxFileStreams = 8,
    )

    init {
        clientDataChannel.connect(serverDataChannel)
        serverDataChannel.connect(clientDataChannel)
    }

    suspend fun <T> use(block: suspend (DeviceSessionPeer, DeviceSessionPeer) -> T): T = coroutineScope {
        val clientChannel = WebRtcDeviceSessionByteChannel(clientDataChannel, CoroutineScope(coroutineContext))
        val serverChannel = WebRtcDeviceSessionByteChannel(serverDataChannel, CoroutineScope(coroutineContext))
        val client = DeviceSessionPeer(
            transport = DeviceSessionTransport(clientChannel, plan, plan, isClient = true),
            receivePlan = plan,
            role = DeviceSessionEndpointRole.Client,
        )
        val server = DeviceSessionPeer(
            transport = DeviceSessionTransport(serverChannel, plan, plan, isClient = false),
            receivePlan = plan,
            role = DeviceSessionEndpointRole.Server,
        )
        client.start(this)
        server.start(this)
        yield()
        try {
            block(client, server)
        } finally {
            client.close()
            server.close()
        }
    }
}

private class CrossWiredSessionDataChannel : WebRtcDataChannel {
    private val incoming = Channel<ByteArray>(capacity = 512)
    private val openEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var peer: CrossWiredSessionDataChannel? = null
    private var open = true

    override val label: String = WEB_RTC_DEVICE_SESSION_CHANNEL_LABEL
    override val state: WebRtcDataChannelState
        get() = if (open) WebRtcDataChannelState.Open else WebRtcDataChannelState.Closed
    override val bufferedAmount: Long = 0L
    override val onOpen: Flow<Unit> = openEvents.asSharedFlow()
    override val onClose: Flow<Unit> = closeEvents.asSharedFlow()
    override val onMessage: Flow<ByteArray> = incoming.receiveAsFlow()

    fun connect(peer: CrossWiredSessionDataChannel) {
        this.peer = peer
    }

    override fun send(data: ByteArray): Boolean {
        if (!open) return false
        val target = peer?.takeIf { it.open } ?: return false
        return target.incoming.trySend(data.copyOf()).isSuccess
    }

    override fun close() {
        closeLocal(notifyPeer = true)
    }

    private fun closeLocal(notifyPeer: Boolean) {
        if (!open) return
        open = false
        incoming.close()
        closeEvents.tryEmit(Unit)
        if (notifyPeer) peer?.closeLocal(notifyPeer = false)
    }
}
