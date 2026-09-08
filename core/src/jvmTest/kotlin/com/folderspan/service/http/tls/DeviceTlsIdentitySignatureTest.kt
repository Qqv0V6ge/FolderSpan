package com.folderspan.service.http.tls

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceTlsIdentitySignatureTest {
    @Test
    fun tlsIdentitySignsWithoutExposingPrivateKey() {
        val publicKey = assertNotNull(DeviceTlsIdentity.publicKeyPem())
        val payload = "account-device-proof".encodeToByteArray()
        val signature = assertNotNull(DeviceTlsIdentity.signSha256WithRsa(payload))

        assertTrue(DeviceTlsIdentity.verifySha256WithRsa(publicKey, payload, signature))
        assertFalse(DeviceTlsIdentity.verifySha256WithRsa(publicKey, "changed".encodeToByteArray(), signature))
    }
}
