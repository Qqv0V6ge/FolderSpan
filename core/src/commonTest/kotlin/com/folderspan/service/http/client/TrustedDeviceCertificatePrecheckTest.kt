package com.folderspan.service.http.client

import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedDeviceCertificatePrecheckTest {
    @Test
    fun changedSavedFingerprintIsBlockedByDefault() {
        SettingsUtils.init(createInMemorySettings())
        val deviceId = "device-1"
        TrustedDeviceCertificateStore.save(deviceId, "AA:BB:CC")

        assertTrue(
            shouldBlockChangedTrustedDeviceCertificate(
                deviceId = deviceId,
                tlsFingerprint = "DD:EE:FF",
                allowChangedTrustedCertificate = false
            )
        )
    }

    @Test
    fun userConfirmedChangedFingerprintCanConnectWithoutPreSavingTrust() {
        SettingsUtils.init(createInMemorySettings())
        val deviceId = "device-1"
        val oldFingerprint = "AA:BB:CC"
        val newFingerprint = "DD:EE:FF"
        TrustedDeviceCertificateStore.save(deviceId, oldFingerprint)

        assertFalse(
            shouldBlockChangedTrustedDeviceCertificate(
                deviceId = deviceId,
                tlsFingerprint = newFingerprint,
                allowChangedTrustedCertificate = true
            )
        )
        assertTrue(TrustedDeviceCertificateStore.verify(deviceId, oldFingerprint))
        assertFalse(TrustedDeviceCertificateStore.verify(deviceId, newFingerprint))
    }
}
