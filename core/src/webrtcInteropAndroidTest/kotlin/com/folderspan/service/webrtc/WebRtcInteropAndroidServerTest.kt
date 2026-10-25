package com.folderspan.service.webrtc

import androidx.test.platform.app.InstrumentationRegistry
import com.folderspan.service.webrtc.models.WebRtcDataChannel
import com.folderspan.service.webrtc.models.WebRtcPeerConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

internal class WebRtcInteropAndroidServerTest : WebRtcInteropBrowserTest() {
    override val initiator: Boolean
        get() = InstrumentationRegistry.getArguments().getString("webrtcInteropInitiator") == "true"

    override suspend fun CoroutineScope.verifyAuthenticatedSession(
        channel: WebRtcDataChannel,
        connection: WebRtcPeerConnection,
        config: WebRtcInteropConfig,
        http: HttpClient,
        bridgeUrl: String,
        remoteFinished: CompletableDeferred<Unit>,
        restart: suspend () -> Unit,
    ) {
        val observations = verifyAuthenticatedDiagnosticServer(
            channel, connection, config, remoteFinished,
            publishBootstrap = { bootstrap ->
                assertEquals(HttpStatusCode.OK, http.post("$bridgeUrl/bootstrap") {
                    setBody(bootstrap?.let { Json.encodeToString(it) }.orEmpty())
                }.status)
            },
            readEvidence = {
                val response = http.get("$bridgeUrl/evidence")
                assertEquals(HttpStatusCode.OK, response.status)
                Json.decodeFromString(response.bodyAsText())
            },
        )
        assertEquals(HttpStatusCode.OK, http.post("$bridgeUrl/observations") {
            setBody(Json { encodeDefaults = true }.encodeToString(observations))
        }.status)
    }
}
