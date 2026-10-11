package com.folderspan.service.session

import com.folderspan.service.webui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import korlibs.crypto.SecureRandom

internal actual suspend fun connectPinnedDeviceSessionChannel(host: String, port: Int, expectedFingerprintSha256: String): DeviceSessionByteChannel {
    val response = WebUiPlatformClient.post<WebUiDeviceConnect, WebUiDeviceConnected>("/devices/connect", WebUiDeviceConnect(host, port, expectedFingerprintSha256))
    return BrowserDeviceChannel(response.handle)
}

internal actual suspend fun connectUnpinnedDeviceSessionChannel(host: String, port: Int): DeviceSessionBootstrapConnection {
    val response = WebUiPlatformClient.post<WebUiDeviceConnect, WebUiDeviceConnected>("/devices/connect", WebUiDeviceConnect(host, port))
    return DeviceSessionBootstrapConnection(BrowserDeviceChannel(response.handle), response.fingerprint)
}

internal class BrowserDeviceChannel(private val handle: String) : DeviceSessionPlatformChannel {
    private val session = WebUiAttachment.sessionToken
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private var closed = false
    private fun checkOpen() { check(!closed && session == WebUiAttachment.sessionToken) { "device channel closed" } }
    override fun frameTransport(sendPlan: DeviceSessionWindowPlan, receivePlan: DeviceSessionWindowPlan, isClient: Boolean, onPing: (suspend (ByteArray) -> Unit)?): DeviceSessionPlatformTransport {
        checkOpen()
        return WebUiDeviceTransport(handle, WebUiDeviceTransportOpen(sendPlan, receivePlan, isClient), onPing)
    }
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = readMutex.withLock {
        checkOpen(); require(offset >= 0 && length >= 0 && length <= buffer.size - offset)
        if (length == 0) return@withLock 0
        val response = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/devices/channel?handle=$handle&length=${minOf(length, WEB_UI_FILE_STREAM_CHUNK_BYTES)}")
        if (response.headers.entries.any { it.key.equals("X-FolderSpan-End", true) && it.value == "true" }) return@withLock -1
        require(response.body.size <= length)
        response.body.copyInto(buffer, offset)
        response.body.size
    }
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = writeMutex.withLock {
        checkOpen(); require(offset >= 0 && length >= 0 && length <= buffer.size - offset)
        var position = offset
        while (position < offset + length) {
            val end = minOf(offset + length, position + WEB_UI_FILE_STREAM_CHUNK_BYTES)
            WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/devices/channel?handle=$handle", buffer.copyOfRange(position, end), "application/octet-stream")
            position = end
        }
    }
    override suspend fun flush() { checkOpen() }
    override fun close() {
        if (closed) return
        closed = true
        // close() is a synchronous channel contract. A failed HTTP cleanup is covered by the session lease.
        CoroutineScope(Dispatchers.Default).launch {
            if (session == WebUiAttachment.sessionToken) withTimeoutOrNull(5_000) { runCatching { WebUiPlatformClient.release(handle) } }
        }
    }
}

internal actual object DeviceLanBeaconUdp {
    actual suspend fun broadcast(payload: ByteArray, port: Int) {
        WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/devices/udp/broadcast?port=$port", payload, "application/octet-stream")
    }
    actual suspend fun receive(port: Int, timeoutMs: Long): List<Pair<String, ByteArray>> {
        require(timeoutMs > 0)
        val packets = mutableListOf<Pair<String, ByteArray>>()
        withTimeoutOrNull(timeoutMs) { listen(port) { host, payload ->
            check(packets.size < WEB_UI_EVENT_LIMIT) { "UDP receive capacity reached" }
            packets += host to payload
        } }
        return packets
    }
    actual suspend fun listen(port: Int, onPacket: suspend (host: String, payload: ByteArray) -> Unit) {
        val id = ByteArray(16).also(SecureRandom::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val failed = CompletableDeferred<Unit>()
        val callbackMutex = Mutex()
        var stopped = false
        var handle: String? = null
        WebUiPlatformClient.register(id, { failed.completeExceptionally(it) }) { event ->
            try {
                check(event.type == "udp-packet") { "UDP listener failed" }
                val packet = WebUiPlatformClient.json.decodeFromString<WebUiUdpPacket>(event.data)
                callbackMutex.withLock { if (!stopped) onPacket(packet.host, packet.payload) }
            } catch (error: Exception) { failed.completeExceptionally(error) }
            byteArrayOf()
        }
        try {
            handle = WebUiPlatformClient.post<WebUiUdpListen, WebUiHandle>("/devices/udp/listen", WebUiUdpListen(id, port)).id
            failed.await()
        } finally {
            withContext(NonCancellable) {
                WebUiPlatformClient.unregister(id)
                callbackMutex.withLock { stopped = true }
                handle?.let { withTimeoutOrNull(5_000) { WebUiPlatformClient.release(it) } }
            }
        }
    }
}
