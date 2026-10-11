package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test

class WebRtcInteropJvmTest {
    @Test
    fun exchangeFilesAndRpcWithBrowser() = runSuspendTest {
        val outgoing = ConcurrentLinkedQueue<String>()
        val incoming = Channel<InteropSignal>(capacity = 128)
        val browserFinished = CompletableDeferred<Unit>()
        val port = checkNotNull(System.getenv("FOLDERSPAN_WEBRTC_INTEROP_PORT")).toInt()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        try {
        server.createContext("/") { exchange ->
            exchange.use {
                it.responseHeaders.add("Access-Control-Allow-Origin", "*")
                it.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                it.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                val response = when {
                    it.requestMethod == "OPTIONS" -> ""
                    it.requestURI.path == "/ready" -> "ready"
                    it.requestURI.path == "/outgoing" -> outgoing.poll().orEmpty()
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
            verifyWebRtcInterop(
                initiator = true,
                sendSignal = { outgoing.add(Json.encodeToString(it)); Unit },
                receiveSignal = { incoming.receive() },
            )
            withTimeout(10_000L) { browserFinished.await() }
        } finally {
            server.stop(0)
            incoming.close()
        }
    }
}
