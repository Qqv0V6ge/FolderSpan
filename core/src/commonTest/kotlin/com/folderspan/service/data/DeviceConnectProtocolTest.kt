package com.folderspan.service.data

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.http.tls.DEVICE_CONNECT_IDENTITY_PURPOSE
import com.folderspan.service.http.tls.DEVICE_IDENTITY_PROOF_VERSION
import com.folderspan.service.http.tls.DeviceIdentityProof
import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceConnectProtocolTest {
    @Test
    fun currentProtocolRoundTripsExplicitAuthorizationFields() {
        SettingsUtils.init(createInMemorySettings())
        val request = DeviceConnectRequest(
            device = SocketDevice(
                id = "device",
                name = "Device",
                pathSeparator = "/",
                type = DeviceType.JVM,
            ),
            authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
            bootstrapAuthorization = DeviceSessionBootstrapAuthorization(
                type = DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
                opaqueAuthorization = "opaque-authorization",
                connectionAttemptId = "attempt-1",
            ),
            identityProof = DeviceIdentityProof(
                version = DEVICE_IDENTITY_PROOF_VERSION,
                purpose = DEVICE_CONNECT_IDENTITY_PURPOSE,
                deviceId = "device",
                fingerprintSha256 = "AABBCC",
                publicKeyPem = "-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----",
                issuedAtEpochSeconds = 1_700_000_000L,
                nonce = "nonce-1",
                signature = "signature",
            ),
        )
        val response = DeviceConnectResponse(
            connectType = DeviceConnectType.APPROVED,
            token = "token",
            authorizationMode = DeviceConnectAuthorizationMode.STANDARD,
        )

        val decodedRequest = ProtoBufCodec.decode<DeviceConnectRequest>(ProtoBufCodec.encode(request))
        val decodedResponse = ProtoBufCodec.decode<DeviceConnectResponse>(ProtoBufCodec.encode(response))

        assertEquals(DeviceConnectAuthorizationMode.STANDARD, decodedRequest.authorizationMode)
        assertEquals("device", decodedRequest.device.id)
        assertEquals("AABBCC", decodedRequest.identityProof?.fingerprintSha256)
        assertEquals(DEVICE_CONNECT_IDENTITY_PURPOSE, decodedRequest.identityProof?.purpose)
        assertEquals(
            DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED,
            decodedRequest.bootstrapAuthorization?.type,
        )
        assertEquals("opaque-authorization", decodedRequest.bootstrapAuthorization?.opaqueAuthorization)
        assertEquals("attempt-1", decodedRequest.bootstrapAuthorization?.connectionAttemptId)
        assertEquals(DeviceConnectType.APPROVED, decodedResponse.connectType)
        assertEquals("token", decodedResponse.token)
        assertEquals(DeviceConnectAuthorizationMode.STANDARD, decodedResponse.authorizationMode)
    }
}
