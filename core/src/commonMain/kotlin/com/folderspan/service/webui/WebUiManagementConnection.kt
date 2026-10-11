package com.folderspan.service.webui

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** One reader keeps replies moving while platform callbacks wait on shared business. */
internal class WebUiManagementConnection private constructor(
    private val client: HttpClient,
    private val socket: WebSocketSession,
    scope: CoroutineScope,
    private val onEvent: suspend (WebUiPlatformEvent) -> Unit,
) {
    private val mutex = Mutex()
    private val writer = Mutex()
    private val capacity = Semaphore(WEB_UI_REQUEST_LIMIT)
    private val pending = mutableMapOf<Long, CompletableDeferred<WebUiHttpExchange>>()
    private var nextId = 0L
    val closed = CompletableDeferred<Throwable>()
    private val reader = scope.launch {
        var failure: Throwable = IllegalStateException("Runtime management connection closed")
        try {
            for (frame in socket.incoming) {
                check(frame is Frame.Binary) { "binary management message required" }
                val packet = WebUiWire.decode(frame.data)
                when (packet.header.type) {
                    "response" -> mutex.withLock { pending.remove(packet.header.id) }?.complete(
                        WebUiHttpExchange(packet.header.status, packet.header.headers, packet.body))
                    "heartbeat" -> send(WebUiWirePacket(WebUiWireHeader("heartbeat")))
                    "event" -> onEvent(WebUiPlatformEvent(packet.header.id, packet.header.handle,
                        packet.header.eventType, if (packet.header.eventType == "device-frames") "" else packet.body.decodeToString(),
                        packet.header.requiresReply, packet.body))
                    else -> error("unexpected management message")
                }
            }
        } catch (error: Throwable) {
            failure = if (error is Exception) error else IllegalStateException("Runtime management transport failed", error)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.values.forEach { it.completeExceptionally(failure) }
                    pending.clear()
                    closed.complete(failure)
                }
                socket.cancel()
                client.close()
            }
        }
    }

    private suspend fun send(packet: WebUiWirePacket) = writer.withLock {
        sendLocked(packet)
    }

    private suspend fun sendLocked(packet: WebUiWirePacket) {
        check(!closed.isCompleted) { "Runtime management connection closed" }
        withTimeout(WEB_UI_RESOURCE_LEASE_MS) { socket.send(Frame.Binary(true, WebUiWire.encode(packet))) }
    }

    suspend fun request(method: String, path: String, body: ByteArray?, contentType: String?): WebUiHttpExchange {
        require(isWebUiCommandRoute(method, path)) { "not a platform command" }
        check(capacity.tryAcquire()) { "management request capacity reached" }
        val reply = CompletableDeferred<WebUiHttpExchange>()
        var id = 0L
        try {
            writer.withLock {
                // Assign the number in the same critical section as the write.
                id = mutex.withLock {
                    check(!closed.isCompleted) { "Runtime management connection closed" }
                    (++nextId).also { pending[it] = reply }
                }
                sendLocked(WebUiWirePacket(WebUiWireHeader("request", id, method, path, contentType.orEmpty()), body ?: byteArrayOf()))
            }
            return reply.await()
        } finally {
            withContext(NonCancellable) {
                val abandoned = mutex.withLock { pending.remove(id) != null }
                try {
                    if (abandoned && !closed.isCompleted) {
                        // A lost write result is never replayed.
                        withTimeoutOrNull(2_000) { runCatching { send(WebUiWirePacket(WebUiWireHeader("cancel", id))) } }
                    }
                } finally { capacity.release() }
            }
        }
    }

    suspend fun reply(id: Long, bytes: ByteArray, failed: Boolean) {
        send(WebUiWirePacket(WebUiWireHeader(if (failed) "reply-failed" else "reply", id), bytes))
    }

    suspend fun close() {
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) { runCatching { socket.close() } }
            socket.cancel()
            client.close()
            reader.cancelAndJoin()
        }
    }

    companion object {
        suspend fun connect(url: String, token: String, scope: CoroutineScope,
            onEvent: suspend (WebUiPlatformEvent) -> Unit): WebUiManagementConnection {
            val client = HttpClient {
                followRedirects = false
                install(WebSockets) {
                    // Browser engines cannot set native frame limits; WebUiWire bounds every message.
                    channels {
                        incoming = bounded(WEB_UI_EVENT_LIMIT, ChannelOverflow.CLOSE)
                        outgoing = bounded(WEB_UI_REQUEST_LIMIT, ChannelOverflow.SUSPEND)
                    }
                }
            }
            var socket: WebSocketSession? = null
            try {
                return withTimeout(10_000) {
                    val opened = client.webSocketSession(url)
                    socket = opened
                    opened.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("auth", token = token)))))
                    val frame = opened.incoming.receive()
                    check(frame is Frame.Binary) { "invalid management authentication response" }
                    val auth = WebUiWire.decode(frame.data)
                    check(auth.header.type == "authenticated" && auth.header.status == 200) { "Runtime management authentication failed" }
                    WebUiManagementConnection(client, opened, scope, onEvent)
                }
            } catch (error: Throwable) {
                socket?.cancel()
                client.close()
                throw if (error is Exception) error else IllegalStateException("Runtime management connection failed", error)
            }
        }
    }
}
