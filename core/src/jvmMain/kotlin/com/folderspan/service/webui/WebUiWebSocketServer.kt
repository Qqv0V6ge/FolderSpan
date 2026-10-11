package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The upgrade callback owns the socket until every connection worker has exited. */
internal class WebUiWebSocketServer(private val handler: WebUiRequestHandler) {
    @OptIn(InternalAPI::class)
    suspend fun upgrade(request: LinkShareHttpRequest, socket: Socket): Boolean {
        if (request.path != WEB_UI_SOCKET_PATH) return false
        val key = request.header("Sec-WebSocket-Key")
        val valid = request.method == "GET" && request.rawUri == WEB_UI_SOCKET_PATH && handler.allowsSocket(request) &&
            request.headers["sec-websocket-key"]?.size == 1 && request.headers["sec-websocket-version"]?.size == 1 &&
            request.header("Sec-WebSocket-Version") == "13" && request.header("Upgrade").equals("websocket", true) &&
            request.header("Connection").orEmpty().split(',').any { it.trim().equals("upgrade", true) } &&
            runCatching { Base64.getDecoder().decode(key).size == 16 }.getOrDefault(false) &&
            request.body.contentLength == 0L
        if (!valid) {
            socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return true
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        socket.getOutputStream().apply {
            write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            flush()
        }
        socket.soTimeout = WEB_UI_RESOURCE_LEASE_MS.toInt()
        coroutineScope {
            val input = socket.getInputStream().toByteReadChannel(coroutineContext + Dispatchers.IO)
            val output = ByteChannel(autoFlush = true)
            val pump = launch(Dispatchers.IO) { output.copyTo(socket.getOutputStream()) }
            val queues = WebSocketChannelsConfig().apply {
                incoming = bounded(WEB_UI_EVENT_LIMIT, ChannelOverflow.CLOSE)
                outgoing = bounded(WEB_UI_REQUEST_LIMIT, ChannelOverflow.SUSPEND)
            }
            val raw = RawWebSocket(input, output, WEB_UI_WIRE_LIMIT.toLong(), false, coroutineContext, queues)
            val ws = DefaultWebSocketSession(raw, 20_000, WEB_UI_RESOURCE_LEASE_MS, queues)
            ws.start()
            var owned: WebUiPlatformSession? = null
            var token = ""
            try {
                val auth = withTimeout(5_000) { receive(ws) }
                require(auth.header.type == "auth" && auth.body.isEmpty() && auth.header.token.length in 1..256)
                val session = handler.attachSocket(auth.header.token, auth.header.version)
                owned = session
                token = auth.header.token
                val tasks = ConcurrentHashMap<Long, Job>()
                val writer = Mutex()
                suspend fun send(packet: WebUiWirePacket, completingId: Long? = null) = writer.withLock {
                    // Waiting responses remain counted until they own the bounded writer.
                    completingId?.let { tasks.remove(it) }
                    withTimeout(WEB_UI_RESOURCE_LEASE_MS) { ws.send(Frame.Binary(true, WebUiWire.encode(packet))) }
                }
                send(WebUiWirePacket(WebUiWireHeader("authenticated", status = 200)))
                var lastId = 0L
                coroutineScope {
                    launch {
                        while (isActive) {
                            val event = session.nextEvent()
                            send(WebUiWirePacket(WebUiWireHeader("event", event.id, handle = event.handle,
                                eventType = event.type, requiresReply = event.requiresReply),
                                if (event.bytes.isNotEmpty()) event.bytes else event.data.encodeToByteArray()))
                        }
                    }
                    launch {
                        while (isActive) {
                            delay(20_000)
                            check(handler.socketAlive(token) && !session.expired()) { "management session expired" }
                            send(WebUiWirePacket(WebUiWireHeader("heartbeat")))
                        }
                    }
                    try {
                        while (isActive) {
                            val packet = receive(ws)
                            check(handler.socketAlive(token)) { "management session expired" }
                            when (packet.header.type) {
                                "heartbeat" -> session.touchLease()
                                "reply", "reply-failed" -> session.reply(packet.header.id, packet.body, packet.header.type == "reply-failed")
                                "cancel" -> tasks[packet.header.id]?.cancel()
                                "request" -> {
                                    require(packet.header.id > lastId) { "management request id invalid" }
                                    lastId = packet.header.id
                                    if (tasks.size >= WEB_UI_REQUEST_LIMIT) {
                                        send(WebUiWirePacket(WebUiWireHeader("response", packet.header.id, status = 429),
                                            """{"code":"capacity","error":"management request capacity reached"}""".encodeToByteArray()))
                                        continue
                                    }
                                    require(packet.header.method in setOf("GET", "POST", "PUT", "DELETE") && isWebUiCommandRoute(packet.header.method, packet.header.path))
                                    val headers = request.headers.filterKeys { it == "host" || it == "origin" } + mapOf(
                                        WEB_UI_SESSION_HEADER.lowercase() to listOf(token),
                                        WEB_UI_PROTOCOL_HEADER.lowercase() to listOf(WEB_UI_PROTOCOL_VERSION.toString()),
                                        "content-type" to listOf(packet.header.contentType),
                                    )
                                    val command = LinkShareHttpRequest.from(packet.header.method, packet.header.path, headers,
                                        request.remoteHost, request.scheme, request.host, request.port, LinkShareHttpRequestBody.Bytes(packet.body))
                                    require(command.encodedPath == command.path && isWebUiCommandRoute(command.method, command.rawUri))
                                    val job = launch(start = CoroutineStart.LAZY) {
                                        try {
                                            val response = handler.handleCommand(command)
                                            val body = when (val value = response.body) {
                                                LinkShareHttpResponseBody.Empty -> byteArrayOf()
                                                is LinkShareHttpResponseBody.Bytes -> value.bytes
                                                is LinkShareHttpResponseBody.Stream -> error("stream body requires HTTP")
                                            }
                                            send(WebUiWirePacket(WebUiWireHeader("response", packet.header.id, status = response.statusCode,
                                                headers = response.headers.associate { it.name.lowercase() to it.value }), body), packet.header.id)
                                        } finally { tasks.remove(packet.header.id) }
                                    }
                                    tasks[packet.header.id] = job
                                    job.start()
                                }
                                else -> error("unexpected management message")
                            }
                        }
                    } finally { coroutineContext[Job]!!.cancelChildren() }
                }
            } finally {
                withContext(NonCancellable) {
                    // Close blocking I/O before waiting for its coroutines.
                    runCatching { socket.close() }
                    ws.cancel()
                    raw.cancel()
                    input.cancel(null)
                    output.cancel(null)
                    pump.cancelAndJoin()
                    if (owned != null) handler.release(token)
                }
            }
        }
        return true
    }

    private suspend fun receive(ws: WebSocketSession): WebUiWirePacket {
        val frame = ws.incoming.receive()
        require(frame is Frame.Binary) { "binary management message required" }
        return WebUiWire.decode(frame.data)
    }
}
