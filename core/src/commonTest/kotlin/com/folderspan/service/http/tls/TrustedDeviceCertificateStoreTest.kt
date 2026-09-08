package com.folderspan.service.http.tls

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedDeviceCertificateStoreTest {
    @Test
    fun settingsKeyStaysShortForLongDeviceId() {
        val longDeviceId = "sphPp8b5mtG8zodD8OaJtRGWjNAFDiWOW9baQ1LfBsrFCkEwYTYhAa20YyFbYcK0"

        val key = TrustedDeviceCertificateStore.settingsKey(longDeviceId)

        assertTrue(key.length <= 80)
        assertTrue(key.startsWith("trustedTlsFp."))
    }

    @Test
    fun settingsKeyIsStable() {
        val deviceId = "device-1"

        assertEquals(
            TrustedDeviceCertificateStore.settingsKey(deviceId),
            TrustedDeviceCertificateStore.settingsKey(deviceId)
        )
    }

    @Test
    fun removeClearsSavedFingerprint() {
        SettingsUtils.init(createInMemorySettings())
        val deviceId = "device-1"
        val fingerprint = "AA:BB:CC"
        TrustedDeviceCertificateStore.save(deviceId, fingerprint)

        TrustedDeviceCertificateStore.remove(deviceId)

        assertFalse(TrustedDeviceCertificateStore.has(deviceId))
        assertFalse(TrustedDeviceCertificateStore.verify(deviceId, fingerprint))
        assertEquals("", TrustedDeviceCertificateStore.expectedPublicKeyPem(deviceId))
    }

    @Test
    fun savePersistsPublicKeyAndRemoveClearsIt() {
        SettingsUtils.init(createInMemorySettings())
        val deviceId = "device-pk"
        val fingerprint = "AA:BB:CC"
        val publicKeyPem = "-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----"

        TrustedDeviceCertificateStore.save(deviceId, fingerprint, publicKeyPem)

        assertTrue(TrustedDeviceCertificateStore.has(deviceId))
        assertTrue(TrustedDeviceCertificateStore.verify(deviceId, fingerprint))
        assertEquals(publicKeyPem, TrustedDeviceCertificateStore.expectedPublicKeyPem(deviceId))

        TrustedDeviceCertificateStore.remove(deviceId)

        assertFalse(TrustedDeviceCertificateStore.has(deviceId))
        assertEquals("", TrustedDeviceCertificateStore.expectedPublicKeyPem(deviceId))
    }
}
