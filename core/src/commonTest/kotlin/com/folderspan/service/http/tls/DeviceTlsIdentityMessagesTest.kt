package com.folderspan.service.http.tls

import io.github.skeptick.libres.LibresSettings
import strings.AppStrings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceTlsIdentityMessagesTest {
    @AfterTest
    fun resetLanguage() {
        LibresSettings.languageCode = "en"
    }

    @Test
    fun serverMismatchMessageFollowsLanguageChanges() {
        assertTracksLanguage(
            { SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE },
            { AppStrings.ui_service_certificate_fingerprint_does_not_match },
        )
    }

    @Test
    fun deviceMismatchMessageFollowsLanguageChanges() {
        assertTracksLanguage(
            { DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE },
            { AppStrings.ui_device_tls_certificate_fingerprint_does_not_match },
        )
    }

    @Test
    fun sharedDeviceMismatchMessageFollowsLanguageChanges() {
        assertTracksLanguage(
            { SHARE_DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE },
            { AppStrings.ui_shared_device_tls_certificate_fingerprint_mismatch },
        )
    }

    private fun assertTracksLanguage(message: () -> String, expected: () -> String) {
        LibresSettings.languageCode = "en"
        normalizeTlsFingerprintSha256("aa:bb")
        val english = message()
        assertEquals(expected(), english)

        LibresSettings.languageCode = "zhHans"
        assertNotEquals(english, expected())
        assertEquals(expected(), message())

        LibresSettings.languageCode = "en"
        assertEquals(english, message())
    }
}
