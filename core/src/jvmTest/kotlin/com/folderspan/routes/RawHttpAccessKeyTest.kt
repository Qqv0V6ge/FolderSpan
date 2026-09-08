package com.folderspan.routes

import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.writeFileShareAccessKeyConfig
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawHttpAccessKeyTest {
    @Test
    fun disabledAccessKeyKeepsExistingRoutingBehavior() = runBlocking {
        val response = RawHttpApiDispatcher(MapSettings()).dispatch(unknownRequest())

        assertEquals(404, response.statusCode)
    }

    @Test
    fun enabledAccessKeyRejectsMissingAndMismatchedValuesWithEmpty403() = runBlocking {
        val settings = protectedSettings()
        val dispatcher = RawHttpApiDispatcher(settings)

        val missing = dispatcher.dispatch(unknownRequest())
        val mismatched = dispatcher.dispatch(
            unknownRequest(mapOf(FILE_SHARE_ACCESS_KEY_HEADER.lowercase() to "wrong-key"))
        )

        assertEquals(403, missing.statusCode)
        assertTrue((missing.body as RawHttpBody.Bytes).bytes.isEmpty())
        assertEquals(403, mismatched.statusCode)
        assertTrue((mismatched.body as RawHttpBody.Bytes).bytes.isEmpty())
    }

    @Test
    fun matchingAccessKeyContinuesToBusinessRouter() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(protectedSettings())

        val response = dispatcher.dispatch(
            unknownRequest(mapOf(FILE_SHARE_ACCESS_KEY_HEADER.lowercase() to "shared-key"))
        )

        assertEquals(404, response.statusCode)
    }

    @Test
    fun corsPreflightBypassesAccessKeyAndAdvertisesFixedHeader() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(protectedSettings())
        val response = dispatcher.dispatch(
            RawHttpRequest(method = "OPTIONS", path = "/api/devices/connect")
        )

        assertEquals(204, response.statusCode)
        assertContains(
            response.headers.getValue("Access-Control-Allow-Headers"),
            FILE_SHARE_ACCESS_KEY_HEADER,
        )
    }

    @Test
    fun webRtcSignalingIsProtectedAndItsPreflightAllowsAccessKeyHeader() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(protectedSettings())
        val rejected = dispatcher.dispatch(
            RawHttpRequest(method = "GET", path = "/api/webrtc/signaling/discover")
        )
        val preflight = dispatcher.dispatch(
            RawHttpRequest(
                method = "OPTIONS",
                path = "/api/webrtc/signaling/discover",
                headers = mapOf(
                    "origin" to "https://127.0.0.1:12040",
                    "host" to "127.0.0.1:12040",
                ),
            )
        )

        assertEquals(403, rejected.statusCode)
        assertEquals(204, preflight.statusCode)
        assertContains(
            preflight.headers.getValue("Access-Control-Allow-Headers"),
            FILE_SHARE_ACCESS_KEY_HEADER,
        )
    }

    private fun protectedSettings(): MapSettings = MapSettings().apply {
        writeFileShareAccessKeyConfig(
            FileShareAccessKeyConfig(enabled = true, value = "shared-key")
        )
    }

    private fun unknownRequest(headers: Map<String, String> = emptyMap()): RawHttpRequest =
        RawHttpRequest(method = "POST", path = "/unknown", headers = headers)
}
