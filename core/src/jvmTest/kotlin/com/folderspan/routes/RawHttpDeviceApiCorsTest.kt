package com.folderspan.routes

import com.folderspan.service.data.DISCOVERY_PING_HEADER
import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RawHttpDeviceApiCorsTest {
    @Test
    fun preflightRejectsArbitraryPrivateOriginAndPrivateNetworkRequest() = runBlocking {
        val origin = "http://192.168.1.20:8080"
        val response = RawHttpApiDispatcher().dispatch(
            deviceApiPreflightRequest(
                origin = origin,
                privateNetwork = true,
                host = "192.168.1.10:12040",
            ),
        )

        assertEquals(403, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
        assertContains(response.headers.getValue("Access-Control-Allow-Headers"), FILE_SHARE_ACCESS_KEY_HEADER)
        assertContains(response.headers.getValue("Access-Control-Allow-Headers"), DISCOVERY_PING_HEADER)
    }

    @Test
    fun preflightAllowsSameHostAndPortBrowserWithoutPrivateNetworkGrant() = runBlocking {
        val origin = "http://192.168.1.20:12040"
        val response = RawHttpApiDispatcher(
            advertisedHostProvider = { setOf("192.168.1.20") },
        ).dispatch(
            deviceApiPreflightRequest(
                origin = origin,
                privateNetwork = true,
                host = "192.168.1.20:12040",
            ),
        )

        assertEquals(204, response.statusCode)
        assertEquals(origin, response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun preflightAllowsLocalhostWithoutWildcard() = runBlocking {
        val origin = "http://localhost:12040"
        val response = RawHttpApiDispatcher().dispatch(
            deviceApiPreflightRequest(
                origin = origin,
                privateNetwork = true,
                host = "localhost:12040",
            ),
        )

        assertEquals(204, response.statusCode)
        assertEquals(origin, response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun preflightRejectsPublicOrigin() = runBlocking {
        val response = RawHttpApiDispatcher().dispatch(
            deviceApiPreflightRequest(origin = "https://example.com", privateNetwork = true),
        )

        assertEquals(403, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun nativeRequestWithoutOriginHasNoAllowOrigin() = runBlocking {
        val response = RawHttpApiDispatcher(settings = MapSettings()).dispatch(
            RawHttpRequest(method = "POST", path = "/unknown"),
        )

        assertEquals(404, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun deviceArchiveHttpEndpointsRemainUnavailable() = runBlocking {
        val dispatcher = RawHttpApiDispatcher(settings = MapSettings())

        listOf("/api/files/archive-download", "/api/files/archive-upload").forEach { path ->
            val response = dispatcher.dispatch(RawHttpRequest(method = "POST", path = path))
            assertEquals(404, response.statusCode, path)
        }
    }

    @Test
    fun privateOriginCannotCallDeviceApi() = runBlocking {
        val origin = "https://10.0.0.122:8080"
        val response = RawHttpApiDispatcher().dispatch(
            RawHttpRequest(
                method = "POST",
                path = "/unknown",
                headers = mapOf(
                    "origin" to origin,
                    "host" to "10.0.0.50:12040",
                ),
            ),
        )

        assertEquals(403, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun preflightRejectsLinkLocalOrigin() = runBlocking {
        val response = RawHttpApiDispatcher().dispatch(
            deviceApiPreflightRequest(
                origin = "http://169.254.10.20",
                privateNetwork = true,
                host = "169.254.10.1:12040",
            ),
        )

        assertEquals(403, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun publicOriginCannotCallDeviceApi() = runBlocking {
        val response = RawHttpApiDispatcher().dispatch(
            RawHttpRequest(
                method = "POST",
                path = "/ping",
                headers = mapOf(
                    "origin" to "https://example.com",
                    "access-control-request-private-network" to "true",
                ),
            ),
        )

        assertEquals(403, response.statusCode)
        assertNull(response.headers["Access-Control-Allow-Origin"])
        assertNull(response.headers["Access-Control-Allow-Private-Network"])
    }

    private fun deviceApiPreflightRequest(
        origin: String,
        path: String = "/ping",
        privateNetwork: Boolean = false,
        host: String? = null,
    ): RawHttpRequest {
        return RawHttpRequest(
            method = "OPTIONS",
            path = path,
            headers = buildMap {
                put("origin", origin)
                put("access-control-request-method", "POST")
                put("access-control-request-headers", "content-type, $DISCOVERY_PING_HEADER")
                host?.let { value -> put("host", value) }
                if (privateNetwork) {
                    put("access-control-request-private-network", "true")
                }
            },
        )
    }
}
