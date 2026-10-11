package com.folderspan.routes

import com.folderspan.ui.state.device.DeviceTokenFingerprint
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RawHttpAuthorizationTest {
    @Test
    fun authenticatedDataRequestRefreshesDeviceSessionLease() = runBlocking {
        var validatedFingerprint: DeviceTokenFingerprint? = null
        var refreshedDeviceId: String? = null
        val request = RawHttpRequest(
            method = "POST",
            path = "/api/paths/list",
            headers = mapOf(
                "authorization" to "Bearer valid-token",
                "user-agent" to "FolderSpan-Test",
            ),
            remoteHost = "10.0.0.107",
        )

        val response = authorizeDeviceRequest(
            request = request,
            resolveDeviceId = { token -> if (token == "valid-token") "device-1" else null },
            validateToken = { token, fingerprint ->
                validatedFingerprint = fingerprint
                token == "valid-token"
            },
            onAuthorized = { deviceId -> refreshedDeviceId = deviceId },
        )

        assertNull(response)
        assertEquals("device-1", refreshedDeviceId)
        assertEquals("device-1", validatedFingerprint?.deviceId)
        assertEquals("10.0.0.107", validatedFingerprint?.clientIp)
        assertEquals("FolderSpan-Test", validatedFingerprint?.userAgent)
    }

    @Test
    fun invalidTokenDoesNotRefreshDeviceSessionLease() = runBlocking {
        var refreshCount = 0

        val response = authorizeDeviceRequest(
            request = RawHttpRequest(
                method = "POST",
                path = "/api/files/create-folder",
                headers = mapOf("authorization" to "Bearer expired-token"),
            ),
            resolveDeviceId = { "device-1" },
            validateToken = { _, _ -> false },
            onAuthorized = { refreshCount++ },
        )

        assertEquals(401, response?.statusCode)
        assertEquals(0, refreshCount)
    }
}
