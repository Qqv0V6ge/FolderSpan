package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class WebRtcInteropBrowserTest {
    @Test
    fun exchangeFilesAndRpcWithNativePeer() = runSuspendTest {
        val port = js("globalThis.__karma__.config.webrtcInteropPort") as String
        require(port.toInt() in 1..65535)
        val url = "http://127.0.0.1:$port"
        val http = HttpClient()
        try {
            verifyWebRtcInterop(
                initiator = false,
                sendSignal = { signal ->
                    check(http.post("$url/incoming") { setBody(Json.encodeToString(signal)) }.status == HttpStatusCode.OK)
                },
                receiveSignal = {
                    var signal: InteropSignal? = null
                    while (signal == null) {
                        val response = http.get("$url/outgoing")
                        if (response.status == HttpStatusCode.OK) {
                            signal = Json.decodeFromString<InteropSignal>(response.bodyAsText())
                        } else {
                            check(response.status == HttpStatusCode.NoContent)
                            delay(25)
                        }
                    }
                    signal
                },
            )
            check(http.post("$url/finished").status == HttpStatusCode.OK)
        } finally {
            http.close()
        }
    }
}
