package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

internal open class WebRtcInteropBrowserTest {
    protected open val initiator: Boolean = false

    @Test
    fun exchangeFilesAndRpcWithNativePeer() = runSuspendTest {
        val url = interopBridgeUrl().trimEnd('/')
        val config = Json.decodeFromString<WebRtcInteropConfig>(interopConfigJson())
        val http = HttpClient()
        try {
            check(http.post("$url/joined").status == HttpStatusCode.OK)
            verifyWebRtcInterop(
                initiator = initiator,
                config = config,
                authenticatedSession = { channel, connection, finished, restart ->
                    verifyAuthenticatedSession(channel, connection, config, http, url, finished, restart)
                },
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

    protected open suspend fun CoroutineScope.verifyAuthenticatedSession(
        channel: WebRtcDataChannel,
        connection: WebRtcPeerConnection,
        config: WebRtcInteropConfig,
        http: HttpClient,
        bridgeUrl: String,
        remoteFinished: CompletableDeferred<Unit>,
        restart: suspend () -> Unit,
    ) {
        verifyAuthenticatedDiagnosticClient(channel, connection, config, http, bridgeUrl, restart)
    }
}
