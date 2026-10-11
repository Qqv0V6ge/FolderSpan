package com.folderspan.service.webui

import com.folderspan.service.http.client.createPinnedNoProxyHttpClient
import com.folderspan.service.http.server.linkshare.*
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.net.ServerSocket
import kotlin.test.*

class WebUiWebSocketTest {
    @Test
    fun exceedingRequestCapacityDoesNotAbortExistingCommands(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val socket = runtime.client.webSocketSession(runtime.url)
                try {
                    socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("auth", token = runtime.login())))))
                    assertEquals("authenticated", WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data).header.type)
                    repeat(WEB_UI_REQUEST_LIMIT) { index ->
                        socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("request", index + 1L, "GET", "$WEB_UI_PLATFORM_PATH/wait")))))
                    }
                    val overflowId = WEB_UI_REQUEST_LIMIT + 1L
                    socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("request", overflowId, "GET", "$WEB_UI_PLATFORM_PATH/fast")))))
                    val overflow = WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data)
                    assertEquals(overflowId, overflow.header.id)
                    assertEquals(429, overflow.header.status)
                    assertFalse(runtime.cancelled.isCompleted)
                } finally { socket.close(); socket.cancel() }
                runtime.cancelled.await()
            }
        }
    }

    @Test
    fun concurrentRequestNumbersFollowWireOrderAtCapacity(): Unit = runBlocking {
        withTimeout(30_000) {
            withRuntime { runtime ->
                val opened = WebUiManagementConnection.connect(runtime.url, runtime.login(), this) {}
                try {
                    repeat(20) {
                        coroutineScope {
                            List(WEB_UI_REQUEST_LIMIT) {
                                async(Dispatchers.Default) {
                                    assertEquals("fast", opened.request("GET", "$WEB_UI_PLATFORM_PATH/fast", null, null).body.decodeToString())
                                }
                            }.awaitAll()
                        }
                    }
                } finally { opened.close() }
            }
        }
    }

    @Test
    fun tlsUpgradeUsesTheSamePortAndPinnedRuntimeCertificate(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val status = runtime.service.status()
                val client = createPinnedNoProxyHttpClient(status.tlsFingerprintSha256) { install(WebSockets) }
                try {
                    val socket = client.webSocketSession {
                        url("wss://127.0.0.1:${runtime.port}$WEB_UI_SOCKET_PATH")
                        header("Origin", "https://127.0.0.1:${runtime.port}")
                    }
                    try {
                        socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("auth", token = runtime.login())))))
                        val authenticated = WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data)
                        assertEquals("authenticated", authenticated.header.type)
                        socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("request", 1, "GET", "$WEB_UI_PLATFORM_PATH/fast")))))
                        val response = WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data)
                        assertEquals(200, response.header.status)
                        assertEquals("fast", response.body.decodeToString())
                    } finally { socket.close(); socket.cancel() }
                } finally { client.close() }
            }
        }
    }

    @Test
    fun progressCoalescesWhileTerminalResultRemainsDeliverable(): Unit = runBlocking {
        val session = WebUiPlatformSession("progress")
        try {
            repeat(100) { session.emitLatest("transfer", "device-progress", it.toString()) }
            assertEquals("99", session.nextEvent().data)
            session.emitLatest("transfer", "device-progress", "100")
            session.finishLatest("transfer", "device-progress", "complete")
            assertEquals("complete", session.nextEvent().data)
            session.emit("other", "approval")
            assertEquals("other", session.nextEvent().handle)
        } finally { session.close() }
    }

    @Test
    fun heartbeatCannotKeepAnExpiredBusinessSessionAuthorized(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val token = runtime.login()
                val socket = runtime.client.webSocketSession(runtime.url)
                try {
                    socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("auth", token = token)))))
                    assertEquals("authenticated", WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data).header.type)
                    socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("request", 1, "POST", "$WEB_UI_PLATFORM_PATH/own")))))
                    assertEquals(200, WebUiWire.decode((socket.incoming.receive() as Frame.Binary).data).header.status)
                    runtime.sessionTime = WEB_UI_SESSION_IDLE_TIMEOUT_MS
                    socket.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(WebUiWireHeader("heartbeat")))))
                    assertFails { socket.incoming.receive() }
                    runtime.released.await()
                    assertNull(runtime.sessions.get(token, false))
                } finally { socket.cancel() }
            }
        }
    }

    @Test
    fun concurrentCommandsAndReverseApprovalShareOneConnection(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                var connection: WebUiManagementConnection? = null
                val opened = WebUiManagementConnection.connect(runtime.url, runtime.login(), this) { event ->
                    assertEquals("approval", event.type)
                    connection!!.reply(event.id, "approved".encodeToByteArray(), false)
                }
                connection = opened
                try {
                    val slow = async { opened.request("GET", "$WEB_UI_PLATFORM_PATH/slow", null, null) }
                    val approval = async { opened.request("GET", "$WEB_UI_PLATFORM_PATH/approval", null, null) }
                    val fast = opened.request("GET", "$WEB_UI_PLATFORM_PATH/fast", null, null)
                    assertEquals("fast", fast.body.decodeToString())
                    assertEquals("approved", approval.await().body.decodeToString())
                    assertEquals("slow", slow.await().body.decodeToString())
                } finally { opened.close() }
            }
        }
    }

    @Test
    fun cancellationOnlyStopsItsRequestAndDisconnectReleasesOwnedResource(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val token = runtime.login()
                val opened = WebUiManagementConnection.connect(runtime.url, token, this) {}
                val waiting = async { opened.request("GET", "$WEB_UI_PLATFORM_PATH/wait", null, null) }
                runtime.started.await()
                waiting.cancelAndJoin()
                runtime.cancelled.await()
                assertEquals("fast", opened.request("GET", "$WEB_UI_PLATFORM_PATH/fast", null, null).body.decodeToString())
                opened.request("POST", "$WEB_UI_PLATFORM_PATH/own", null, null)
                opened.close()
                runtime.released.await()
                assertNull(runtime.sessions.get(token, false))
            }
        }
    }

    @Test
    fun secondConnectionCannotTakeOverAndOldPollingIsRejected(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val token = runtime.login()
                val opened = WebUiManagementConnection.connect(runtime.url, token, this) {}
                try {
                    assertFails { WebUiManagementConnection.connect(runtime.url, token, this) {} }
                    assertEquals("fast", opened.request("GET", "$WEB_UI_PLATFORM_PATH/fast", null, null).body.decodeToString())
                    val old = runtime.client.get(runtime.http + WEB_UI_EVENTS_PATH) {
                        header(WEB_UI_SESSION_HEADER, token)
                        header(WEB_UI_PROTOCOL_HEADER, WEB_UI_PROTOCOL_VERSION)
                    }
                    assertEquals(426, old.status.value)
                    assertFailsWith<IllegalArgumentException> { opened.request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=x", null, null) }
                } finally { opened.close() }
            }
        }
    }

    @Test
    fun foreignOriginAndUnauthenticatedCommandsDoNotExecute(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                assertFails { runtime.client.webSocketSession { url(runtime.url); header("Origin", "https://foreign.example") } }
                val unauthenticated = runtime.client.webSocketSession(runtime.url)
                unauthenticated.send(Frame.Binary(true, WebUiWire.encode(WebUiWirePacket(
                    WebUiWireHeader("request", 1, "POST", "$WEB_UI_PLATFORM_PATH/own")))))
                assertFails { unauthenticated.incoming.receive() }
                unauthenticated.cancel()
                assertFalse(runtime.owned)
                assertFails { WebUiManagementConnection.connect(runtime.url, "expired", this) {} }
            }
        }
    }

    @Test
    fun stoppingServiceFailsPendingOperationsAndClosesConnection(): Unit = runBlocking {
        withTimeout(20_000) {
            withRuntime { runtime ->
                val opened = WebUiManagementConnection.connect(runtime.url, runtime.login(), this) {}
                try {
                    val pending = async { runCatching { opened.request("GET", "$WEB_UI_PLATFORM_PATH/wait", null, null) } }
                    runtime.started.await()
                    runtime.service.stop()
                    assertTrue(pending.await().isFailure)
                    opened.closed.await()
                    runtime.cancelled.await()
                } finally { opened.close() }
            }
        }
    }

    private suspend fun withRuntime(block: suspend CoroutineScope.(Runtime) -> Unit) = coroutineScope {
        val runtime = Runtime()
        try {
            assertTrue(runtime.service.start(runtime.port).running)
            block(runtime)
        } finally { runtime.service.stop(); runtime.client.close() }
    }

    private class Runtime {
        val port = ServerSocket(0).use { it.localPort }
        val http = "http://127.0.0.1:$port"
        val url = "ws://127.0.0.1:$port$WEB_UI_SOCKET_PATH"
        @Volatile var sessionTime = 0L
        val sessions = WebUiSessionStore(nowMillis = { sessionTime })
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var owned = false
        val client = HttpClient { install(WebSockets); expectSuccess = false }
        private val settings = MapSettings().also(SettingsUtils::init)
        private val credentials = WebUiCredentialStore(settings)
        private val secret = credentials.ensureInitialized().oneTimeSecret!!
        private val platform = object : WebUiPlatform {
            override suspend fun hostInfo(session: WebUiPlatformSession) = WebUiHostInfo("/", "/", "/", "/", emptyList())
            override suspend fun handle(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
                return when (request.path.removePrefix(WEB_UI_PLATFORM_PATH)) {
                    "/slow" -> { delay(300); LinkShareHttpResponse.text(text = "slow") }
                    "/approval" -> LinkShareHttpResponse.bytes(bytes = session.callback("browser", "approval", "question"))
                    "/wait" -> {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }
                    "/own" -> {
                        owned = true
                        session.own(Any()) { released.complete(Unit) }
                        LinkShareHttpResponse.text(text = "owned")
                    }
                    else -> LinkShareHttpResponse.text(text = "fast")
                }
            }
        }
        val service = WebUiHttpService(WebUiRequestHandler(credentials, sessions,
            WebUiSecurityPolicy { setOf("127.0.0.1") }, PlaceholderWebUiDocumentRoot(), platform))
        suspend fun login(): String {
            val reply = client.post(http + WEB_UI_LOGIN_PATH) {
                header("Content-Type", "application/json")
                setBody(WebUiPlatformClient.json.encodeToString(WebUiLoginRequest(secret, WEB_UI_PROTOCOL_VERSION)))
            }
            assertEquals(200, reply.status.value)
            return WebUiPlatformClient.json.decodeFromString<WebUiLoginResponse>(reply.bodyAsText()).sessionToken
        }
    }
}
