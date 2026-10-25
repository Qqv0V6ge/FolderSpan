package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

class WebRtcInteropJvmTest {
    @Test
    fun exchangeFilesAndRpcWithBrowser() = runSuspendTest {
        val outgoing = ConcurrentLinkedQueue<String>()
        val incoming = Channel<InteropSignal>(capacity = 128)
        val browserFinished = CompletableDeferred<Unit>()
        val browserReady = CompletableDeferred<Unit>()
        val bootstrap = AtomicReference<String?>(null)
        val browserEvidence = AtomicReference<WebRtcInteropEvidence?>(null)
        val observations = AtomicReference<List<WebRtcInteropEvidence>?>(null)
        val nativeClient = System.getenv("FOLDERSPAN_WEBRTC_INTEROP_NATIVE_CLIENT") == "1"
        val config = System.getenv("FOLDERSPAN_WEBRTC_INTEROP_CONFIG")?.let { Json.decodeFromString<WebRtcInteropConfig>(it) } ?: WebRtcInteropConfig()
        val evidenceJson = Json { encodeDefaults = true }
        val port = checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_PORT")).toInt()
        val server = HttpServer.create(InetSocketAddress(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_BIND") ?: "127.0.0.1", port), 0)
        try {
            server.createContext("/") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Access-Control-Allow-Origin", "*")
                    it.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    it.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                    val response = when {
                        it.requestMethod == "OPTIONS" -> ""
                        it.requestURI.path == "/ready" -> "ready"
                        it.requestURI.path == "/joined" && it.requestMethod == "POST" -> { browserReady.complete(Unit); "ok" }
                        it.requestURI.path == "/outgoing" -> outgoing.poll().orEmpty()
                        it.requestURI.path == "/bootstrap" && it.requestMethod == "POST" -> {
                            bootstrap.set(it.requestBody.bufferedReader().use { reader -> reader.readText() })
                            "ok"
                        }
                        it.requestURI.path == "/bootstrap" -> bootstrap.get().orEmpty()
                        it.requestURI.path == "/evidence" && it.requestMethod == "GET" -> browserEvidence.get()?.let { value -> Json.encodeToString(value) }.orEmpty()
                        it.requestURI.path == "/observations" && it.requestMethod == "POST" -> {
                            observations.set(Json.decodeFromString(it.requestBody.bufferedReader().use { reader -> reader.readText() }))
                            "ok"
                        }
                        it.requestURI.path == "/evidence" && it.requestMethod == "POST" -> {
                            browserEvidence.set(Json.decodeFromString<WebRtcInteropEvidence>(it.requestBody.bufferedReader().use { reader -> reader.readText() }))
                            "ok"
                        }
                        it.requestURI.path == "/finished" && it.requestMethod == "POST" -> "ok"
                        it.requestURI.path == "/incoming" && it.requestMethod == "POST" -> {
                            val json = it.requestBody.bufferedReader().use { reader -> reader.readText() }
                            check(incoming.trySend(Json.decodeFromString<InteropSignal>(json)).isSuccess)
                            "ok"
                        }
                        else -> error("Unexpected test bridge request")
                    }
                    val bytes = response.toByteArray()
                    it.sendResponseHeaders(if (bytes.isEmpty()) 204 else 200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) it.responseBody.write(bytes)
                    it.responseBody.close()
                    if (it.requestURI.path == "/finished") browserFinished.complete(Unit)
                }
            }
            server.start()
            withTimeout(600_000L) { browserReady.await() }
            verifyWebRtcInterop(
                initiator = true,
                sendSignal = { outgoing.add(Json.encodeToString(it)); Unit },
                receiveSignal = { incoming.receive() },
                config = config,
                authenticatedSession = { channel, connection, remoteFinished, restart ->
                    if (nativeClient) {
                        val http = HttpClient()
                        try {
                            verifyAuthenticatedDiagnosticClient(
                                channel, connection, config, http, checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_URL")), restart,
                            )
                        } finally { http.close() }
                    } else {
                        observations.set(verifyAuthenticatedDiagnosticServer(
                            channel, connection, config, remoteFinished,
                            publishBootstrap = { value -> bootstrap.set(value?.let { Json.encodeToString(it) }) },
                            readEvidence = { checkNotNull(browserEvidence.get()) },
                        ))
                    }
                },
            )
            withTimeout(10_000L) { browserFinished.await() }
            checkNotNull(observations.get()).forEach { evidence ->
                println("WEBRTC_ICE_EVIDENCE " + evidenceJson.encodeToString(evidence))
            }
        } finally {
            server.stop(0)
            incoming.close()
        }
    }
}
