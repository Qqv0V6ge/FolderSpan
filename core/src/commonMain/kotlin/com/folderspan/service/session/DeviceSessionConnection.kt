package com.folderspan.service.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val DEVICE_SESSION_CREDIT_WAIT_TIMEOUT_MILLIS = 120_000L

internal class DeviceSessionClosedException(
    message: String = "device session closed",
) : IllegalStateException(message)

internal class DeviceSessionStreamLimitException(
    message: String = "device session stream limit exceeded",
) : IllegalStateException(message)

internal data class DeviceSessionGoAway(
    val lastStreamId: Int,
)

internal class DeviceSessionConnection(
    private val outgoing: SendChannel<DeviceSessionFrame>,
    private val incoming: ReceiveChannel<DeviceSessionFrame>,
    private val sendCredit: DeviceSessionCredit,
    private val isClient: Boolean,
    private val maxActiveStreams: Int = DEVICE_SESSION_MAX_ACTIVE_STREAMS,
    incomingFrameCapacity: Int = DEVICE_SESSION_MAX_FRAME_QUEUE_CAPACITY,
    private val creditWaitTimeoutMillis: Long = DEVICE_SESSION_CREDIT_WAIT_TIMEOUT_MILLIS,
    private val onPing: (suspend (ByteArray) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val openStreams = HashSet<Int>()
    private val incomingFrames = Channel<DeviceSessionFrame>(capacity = incomingFrameCapacity.coerceAtLeast(1))
    private val pendingPings = HashMap<String, CompletableDeferred<ByteArray>>()
    private var nextStreamIndex = 0
    private var lastStreamId = DEVICE_SESSION_CONTROL_STREAM_ID
    private var closed = false
    private var goAway: DeviceSessionGoAway? = null

    val incomingEvents: ReceiveChannel<DeviceSessionFrame>
        get() = incomingFrames

    suspend fun openStream(
        payload: ByteArray = ByteArray(0),
        onStreamAllocated: suspend (Int) -> Unit = {},
    ): Int {
        val streamId = mutex.withLock {
            ensureOpenLocked()
            if (openStreams.size >= maxActiveStreams) {
                throw DeviceSessionStreamLimitException()
            }
            val id = if (isClient) {
                DeviceSessionFrames.clientStreamId(nextStreamIndex)
            } else {
                DeviceSessionFrames.serverStreamId(nextStreamIndex)
            }
            nextStreamIndex += 1
            openStreams += id
            lastStreamId = maxOf(lastStreamId, id)
            id
        }
        if (payload.size > DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            mutex.withLock { openStreams.remove(streamId) }
            throw IllegalArgumentException("OPEN payload exceeds $DEVICE_SESSION_MAX_PAYLOAD_BYTES bytes")
        }
        try {
            onStreamAllocated(streamId)
            send(DeviceSessionFrame(type = DeviceSessionFrameType.Open, streamId = streamId, payload = payload))
        } catch (error: Throwable) {
            closeLocalStream(streamId)
            throw error
        }
        return streamId
    }

    suspend fun acceptRemoteStream(streamId: Int): Boolean {
        return mutex.withLock {
            ensureOpenLocked()
            openStreams.contains(streamId) || if (openStreams.size >= maxActiveStreams) {
                false
            } else {
                openStreams += streamId
                lastStreamId = maxOf(lastStreamId, streamId)
                true
            }
        }
    }

    suspend fun sendData(streamId: Int, payload: ByteArray) {
        if (payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            sendDataFrame(streamId, payload)
            return
        }
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + DEVICE_SESSION_MAX_PAYLOAD_BYTES, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            sendDataFrame(streamId, chunk)
            offset = end
        }
    }

    suspend fun sendControl(payload: ByteArray) {
        val enveloped = DeviceSessionProtocol.envelopeControl(payload)
        var offset = 0
        while (offset < enveloped.size) {
            val end = minOf(offset + DEVICE_SESSION_MAX_PAYLOAD_BYTES, enveloped.size)
            send(
                DeviceSessionFrame(
                    type = DeviceSessionFrameType.Data,
                    streamId = DEVICE_SESSION_CONTROL_STREAM_ID,
                    payload = enveloped.copyOfRange(offset, end),
                )
            )
            offset = end
        }
    }

    suspend fun sendTrailer(streamId: Int, payload: ByteArray = ByteArray(0)) {
        val bounded = if (payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            payload
        } else {
            payload.copyOf(DEVICE_SESSION_MAX_PAYLOAD_BYTES)
        }
        send(DeviceSessionFrame(type = DeviceSessionFrameType.Trailer, streamId = streamId, payload = bounded))
        closeLocalStream(streamId)
    }

    suspend fun sendWindowUpdate(streamId: Int, creditBytes: Int) {
        send(DeviceSessionFrames.windowUpdate(streamId, creditBytes))
    }


    suspend fun sendRst(streamId: Int, errorCode: Int = 0) {
        send(DeviceSessionFrames.rst(streamId, errorCode))
        closeLocalStream(streamId)
    }

    suspend fun ping(payload: ByteArray): ByteArray {
        val bounded = if (payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            payload
        } else {
            payload.copyOf(DEVICE_SESSION_MAX_PAYLOAD_BYTES)
        }
        val key = pingKey(bounded)
        val waiter = mutex.withLock {
            ensureOpenLocked()
            pendingPings.getOrPut(key) { CompletableDeferred() }
        }
        send(DeviceSessionFrames.ping(bounded))
        return waiter.await()
    }

    suspend fun goAway() {
        val lastId = mutex.withLock {
            ensureOpenLocked()
            lastStreamId
        }
        send(DeviceSessionFrames.goAway(lastId))
        close()
    }

    suspend fun close() {
        val waiters = mutex.withLock {
            if (closed) return
            closed = true
            openStreams.clear()
            pendingPings.values.toList().also { pendingPings.clear() }
        }
        sendCredit.wakeWaiters()
        waiters.forEach { waiter ->
            waiter.completeExceptionally(DeviceSessionClosedException())
        }
        incomingFrames.close()
        outgoing.close()
    }

    suspend fun run() {
        try {
            for (frame in incoming) {
                if (!dispatch(frame)) {
                    break
                }
            }
        } finally {
            close()
        }
    }

    suspend fun receivedGoAway(): DeviceSessionGoAway? = mutex.withLock { goAway }

    private suspend fun dispatch(frame: DeviceSessionFrame): Boolean {
        when (frame.type) {
            DeviceSessionFrameType.WindowUpdate -> {
                val credit = if (frame.payload.size >= 4) {
                    DeviceSessionFrames.readIntLe(frame.payload)
                } else {
                    0
                }
                sendCredit.grant(frame.streamId, credit)
            }
            DeviceSessionFrameType.Ping -> {
                val bounded = if (frame.payload.size <= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
                    frame.payload
                } else {
                    frame.payload.copyOf(DEVICE_SESSION_MAX_PAYLOAD_BYTES)
                }
                onPing?.invoke(bounded)
                send(DeviceSessionFrames.pong(bounded.copyOf()))
            }
            DeviceSessionFrameType.Pong -> {
                val waiter = mutex.withLock {
                    pendingPings.remove(pingKey(frame.payload))
                }
                waiter?.complete(frame.payload)
            }
            DeviceSessionFrameType.Open -> {
                if (!acceptRemoteStream(frame.streamId)) {
                    send(DeviceSessionFrames.rst(frame.streamId, errorCode = 1))
                    return true
                }
                if (!emitIncoming(frame)) return false
            }
            DeviceSessionFrameType.Rst -> {
                closeLocalStream(frame.streamId)
                if (!emitIncoming(frame)) return false
            }
            DeviceSessionFrameType.Trailer -> {
                closeLocalStream(frame.streamId)
                if (!emitIncoming(frame)) return false
            }
            DeviceSessionFrameType.GoAway -> {
                val lastId = if (frame.payload.size >= 4) {
                    DeviceSessionFrames.readIntLe(frame.payload)
                } else {
                    DEVICE_SESSION_CONTROL_STREAM_ID
                }
                mutex.withLock { goAway = DeviceSessionGoAway(lastId) }
                emitIncoming(frame)
                return false
            }
            DeviceSessionFrameType.Data -> if (!emitIncoming(frame)) return false
        }
        return true
    }

    private suspend fun emitIncoming(frame: DeviceSessionFrame): Boolean {
        return try {
            incomingFrames.send(frame)
            true
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    private suspend fun send(frame: DeviceSessionFrame) {
        if (frame.payload.size > DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException(
                "session frame ${frame.type} payload exceeds $DEVICE_SESSION_MAX_PAYLOAD_BYTES bytes",
            )
        }
        mutex.withLock { ensureOpenLocked() }
        outgoing.send(frame)
    }

    private suspend fun sendDataFrame(streamId: Int, payload: ByteArray) {
        currentCoroutineContext().ensureActive()
        ensureStreamOpen(streamId)
        val acquired = sendCredit.tryConsume(streamId, payload.size) || withTimeoutOrNull(creditWaitTimeoutMillis) {
            sendCredit.awaitAndConsume(streamId, payload.size) { ensureStreamOpen(streamId) }
            true
        } ?: false
        if (!acquired) {
            throw DeviceSessionIoException(
                "stream credit timeout: stream=$streamId timeoutMs=$creditWaitTimeoutMillis",
            )
        }
        try {
            ensureStreamOpen(streamId)
            send(DeviceSessionFrame(type = DeviceSessionFrameType.Data, streamId = streamId, payload = payload))
        } catch (error: Throwable) {
            sendCredit.grant(streamId, payload.size)
            throw error
        }
    }

    private suspend fun ensureStreamOpen(streamId: Int) {
        mutex.withLock {
            ensureOpenLocked()
            if (streamId !in openStreams) {
                throw DeviceSessionClosedException("device session stream closed: $streamId")
            }
        }
    }

    private suspend fun closeLocalStream(streamId: Int) {
        mutex.withLock { openStreams.remove(streamId) }
        sendCredit.closeStream(streamId)
    }

    private fun ensureOpenLocked() {
        if (closed) throw DeviceSessionClosedException()
    }

    private fun pingKey(payload: ByteArray): String = payload.joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xFF
        value.toString(16).padStart(2, '0')
    }
}

internal class InMemoryDeviceSessionPair(
    clientPlan: DeviceSessionWindowPlan = DeviceSessionWindowPlan.fromMemory(),
    serverPlan: DeviceSessionWindowPlan = clientPlan,
    maxActiveStreams: Int = DEVICE_SESSION_MAX_ACTIVE_STREAMS,
) {
    private val toServer = Channel<DeviceSessionFrame>(capacity = Channel.UNLIMITED)
    private val toClient = Channel<DeviceSessionFrame>(capacity = Channel.UNLIMITED)

    val clientCredit = DeviceSessionCredit(clientPlan)
    val serverCredit = DeviceSessionCredit(serverPlan)
    val client = DeviceSessionConnection(
        outgoing = toServer,
        incoming = toClient,
        sendCredit = clientCredit,
        isClient = true,
        maxActiveStreams = maxActiveStreams,
    )
    val server = DeviceSessionConnection(
        outgoing = toClient,
        incoming = toServer,
        sendCredit = serverCredit,
        isClient = false,
        maxActiveStreams = maxActiveStreams,
    )

    suspend fun <T> use(block: suspend (client: DeviceSessionConnection, server: DeviceSessionConnection) -> T): T {
        return coroutineScope {
            val clientJob = launch { client.run() }
            val serverJob = launch { server.run() }
            try {
                block(client, server)
            } finally {
                client.close()
                server.close()
                clientJob.cancel()
                serverJob.cancel()
            }
        }
    }
}
