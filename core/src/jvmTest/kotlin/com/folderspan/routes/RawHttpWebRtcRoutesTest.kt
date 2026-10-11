package com.folderspan.routes

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawHttpWebRtcRoutesTest {
    @Test
    fun deviceListenerDoesNotExposeWebRtcSignalingHttp() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(settings = MapSettings())
        val paths = listOf(
            "/api/webrtc/signaling/discover",
            "/api/webrtc/signaling/join",
            "/api/webrtc/signaling/poll",
            "/api/webrtc/signaling/send",
            "/api/webrtc/signaling/leave",
        )
        paths.forEach { path ->
            val response = dispatcher.dispatch(
                RawHttpRequest(
                    method = if (path.endsWith("discover") || path.endsWith("poll")) "GET" else "POST",
                    path = path,
                    protocolVersion = "HTTP/1.1",
                    headers = mapOf("content-type" to "application/json"),
                )
            )
            assertTrue(response.statusCode == 404 || response.statusCode == 405, path)
        }
    }
}
