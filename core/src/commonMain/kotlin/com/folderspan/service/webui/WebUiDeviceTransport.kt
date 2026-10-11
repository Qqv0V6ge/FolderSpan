package com.folderspan.service.webui

import com.folderspan.service.http.archive.FolderSpanArchiveRemoteWriteException
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import korlibs.crypto.SecureRandom

@Serializable
internal data class WebUiDeviceTransportOpen(val sendPlan: DeviceSessionWindowPlan, val receivePlan: DeviceSessionWindowPlan, val isClient: Boolean)

@Serializable
internal data class WebUiDeviceOperation(val operation: String, val streamId: Int = 0, val payload: ByteArray = byteArrayOf(), val value: Int = 0)

@Serializable
internal data class WebUiDeviceOperationResult(val streamId: Int = 0, val payload: ByteArray = byteArrayOf(), val accepted: Boolean = true)

@Serializable
internal data class WebUiDeviceTransferStatus(
    val complete: Boolean,
    val progress: Map<String, Long> = emptyMap(),
    val result: FolderSpanArchiveTransferResult = FolderSpanArchiveTransferResult(),
    val error: String = "",
)

@Serializable
internal data class WebUiDevicePrepareEntry(val path: String, val size: Long, val directory: Boolean)

/** Only control frames and transfer descriptions cross this management connection. */
internal class WebUiDeviceTransport(
    private val handle: String,
    private val options: WebUiDeviceTransportOpen,
    private val onPing: (suspend (ByteArray) -> Unit)? = null,
    private val exchange: suspend (String, String, ByteArray?) -> WebUiHttpExchange = { method, path, body ->
        WebUiPlatformClient.exchange(method, path, body, if (body != null) "application/json" else null)
    },
    private val subscribe: suspend (String, (Throwable) -> Unit, suspend (WebUiPlatformEvent) -> ByteArray) -> Unit =
        { id, failed, listener -> WebUiPlatformClient.register(id, failed, listener) },
    private val unsubscribe: suspend (String) -> Unit = WebUiPlatformClient::unregister,
) : DeviceSessionPlatformTransport, DeviceSessionFrameConnection {
    override val connection: DeviceSessionFrameConnection get() = this
    override val supportsHostTransfers = true
    override val hostTransportHandle get() = handle
    private val incoming = Channel<DeviceSessionFrame>(options.receivePlan.frameQueueCapacity())
    override val incomingEvents: ReceiveChannel<DeviceSessionFrame> get() = incoming
    private val ready = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var closed = false
    private var goAway: DeviceSessionGoAway? = null
    private val controlMutex = Mutex()
    private val base = "$WEB_UI_PLATFORM_PATH/devices/transport?handle=${encodeWebUiQuery(handle)}"

    private suspend inline fun <reified T> post(path: String, input: T): WebUiHttpExchange =
        exchange("POST", path, WebUiPlatformClient.json.encodeToString(input).encodeToByteArray())

    override fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            check(!closed)
            val ended = CompletableDeferred<Unit>()
            subscribe(handle, { incoming.close(it); ended.completeExceptionally(it) }) { event ->
                if (event.type == "device-end") ended.complete(Unit)
                else {
                    check(event.type == "device-frames")
                    for (item in DeviceSessionFrames.decodeAll(event.bytes)) {
                        val frame = if (item.type == DeviceSessionFrameType.Data && item.streamId != 0)
                            DeviceSessionFrame(DeviceSessionFrameType.Data, item.streamId, exchange("GET", "$base&operation=body&streamId=${item.streamId}", null).body)
                        else item
                        if (frame.type == DeviceSessionFrameType.Ping) onPing?.invoke(frame.payload)
                        else {
                            if (frame.type == DeviceSessionFrameType.GoAway) goAway = DeviceSessionGoAway(DeviceSessionFrames.readIntLe(frame.payload))
                            incoming.send(frame)
                        }
                    }
                }
                byteArrayOf()
            }
            post(base, options)
            ready.complete(Unit)
            ended.await()
        } catch (error: Throwable) {
            ready.completeExceptionally(error)
            incoming.close(error)
            if (error is CancellationException) throw error
        } finally {
            incoming.close()
            withContext(NonCancellable) { close() }
        }
    }.also { check(job == null); job = it }

    private suspend fun operation(operation: String, streamId: Int = 0, payload: ByteArray = byteArrayOf(), value: Int = 0): WebUiDeviceOperationResult {
        ready.await()
        check(!closed) { "device transport closed" }
        return WebUiPlatformClient.json.decodeFromString(post("$base&operation=control", WebUiDeviceOperation(operation, streamId, payload, value)).body.decodeToString())
    }

    override suspend fun openStream(payload: ByteArray, onStreamAllocated: suspend (Int) -> Unit): Int {
        val id = operation("allocate", payload = payload).streamId
        try {
            onStreamAllocated(id)
            operation("start", id)
            return id
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { sendRst(id) } }
            throw error
        }
    }
    override suspend fun acceptRemoteStream(streamId: Int) = operation("accept", streamId).accepted
    override suspend fun sendData(streamId: Int, payload: ByteArray) {
        if (payload.isEmpty()) exchange("POST", "$base&operation=data&streamId=$streamId", payload)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + DEVICE_SESSION_MAX_PAYLOAD_BYTES, payload.size)
            exchange("POST", "$base&operation=data&streamId=$streamId", payload.copyOfRange(offset, end))
            offset = end
        }
    }
    override suspend fun sendControl(payload: ByteArray) = controlMutex.withLock {
        // Keep large RPC envelopes within the management request bound; frames stay on the host.
        val envelope = DeviceSessionProtocol.envelopeControl(payload)
        var offset = 0
        while (offset < envelope.size) {
            val end = minOf(offset + DEVICE_SESSION_MAX_PAYLOAD_BYTES, envelope.size)
            operation("control-frame", payload = envelope.copyOfRange(offset, end))
            offset = end
        }
    }
    override suspend fun sendTrailer(streamId: Int, payload: ByteArray) { operation("trailer", streamId, payload) }
    override suspend fun sendWindowUpdate(streamId: Int, creditBytes: Int) { operation("window", streamId, value = creditBytes) }
    override suspend fun sendRst(streamId: Int, errorCode: Int) { operation("reset", streamId, value = errorCode) }
    override suspend fun ping(payload: ByteArray) = operation("ping", payload = payload).payload
    override suspend fun goAway() { operation("go-away") }
    override suspend fun receivedGoAway() = goAway
    override suspend fun close() {
        if (closed) return
        closed = true
        withTimeoutOrNull(5_000) { runCatching { exchange("DELETE", "$WEB_UI_PLATFORM_PATH/resources?handle=${encodeWebUiQuery(handle)}", null) } }
        if (currentCoroutineContext()[Job] != job) job?.cancel()
        unsubscribe(handle)
        incoming.close()
    }

    override suspend fun hostTransfer(
        request: DeviceSessionHostTransfer,
        onProgress: suspend (Map<String, Long>) -> Unit,
        prepareEntry: (suspend (String, Long, Boolean) -> Unit)?,
        isPaused: suspend () -> Boolean,
    ): FolderSpanArchiveTransferResult {
        ready.await()
        val callback = ByteArray(16).also(SecureRandom::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (prepareEntry != null) subscribe(callback, {}) { event ->
            check(event.type == "device-prepare-entry")
            val entry = WebUiPlatformClient.json.decodeFromString<WebUiDevicePrepareEntry>(event.data)
            prepareEntry(entry.path, entry.size, entry.directory)
            byteArrayOf()
        }
        val progressId = "$callback-progress"
        val updates = Channel<WebUiDeviceTransferStatus>(Channel.CONFLATED)
        subscribe(progressId, { updates.close(it) }) { event ->
            check(event.type == "device-progress")
            check(updates.trySend(WebUiPlatformClient.json.decodeFromString(event.data)).isSuccess)
            byteArrayOf()
        }
        var transfer: String? = null
        try {
            val initiallyPaused = isPaused()
            transfer = WebUiPlatformClient.json.decodeFromString<WebUiHandle>(
                post("$base&operation=transfer", request.copy(callbackId = if (prepareEntry == null) "" else callback, progressCallbackId = progressId, initiallyPaused = initiallyPaused)).body.decodeToString(),
            ).id
            val path = "$WEB_UI_PLATFORM_PATH/devices/transfers?handle=${encodeWebUiQuery(transfer)}"
            return coroutineScope {
                val control = launch {
                    var previous = initiallyPaused
                    while (isActive) {
                        val paused = isPaused()
                        if (paused != previous) {
                            exchange("POST", "$path&pause=$paused", null)
                            previous = paused
                        }
                        delay(100)
                    }
                }
                try {
                    while (true) {
                        val status = updates.receive()
                        onProgress(status.progress)
                        if (status.complete) {
                            if (status.error.isNotBlank()) throw FolderSpanArchiveRemoteWriteException(status.error, status.result)
                            return@coroutineScope status.result
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("transfer ended without a result")
                } finally { control.cancelAndJoin() }
            }
        } finally {
            withContext(NonCancellable) {
                unsubscribe(callback)
                unsubscribe(progressId)
                updates.cancel()
                transfer?.let { withTimeoutOrNull(5_000) { runCatching { exchange("DELETE", "$WEB_UI_PLATFORM_PATH/resources?handle=${encodeWebUiQuery(it)}", null) } } }
            }
        }
    }
}
