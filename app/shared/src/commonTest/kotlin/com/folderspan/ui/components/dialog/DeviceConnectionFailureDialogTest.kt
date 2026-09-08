package com.folderspan.ui.components.dialog

import strings.AppStrings

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceConnectionFailureDialogTest : ChineseLocalizationTest() {
    @Test
    fun savedFingerprintMismatchOffersTrustNewCertificateAction() {
        val dialog = buildHttpDeviceConnectionFailureDialogOrNull(
            testDevice(),
            IllegalStateException(AppStrings.ui_device_tls_certificate_fingerprint_does_not_match)
        )

        assertNotNull(dialog)
        assertEquals(AppStrings.ui_trust_connect, dialog.confirmText)
        assertContains(dialog.message, AppStrings.ui_test_device_connection_failure_dialog_certificate_fingerprint_has_changed)
        assertContains(dialog.message, AppStrings.ui_test_device_connection_failure_dialog_confirm_this_is_the_same_trustworthy)
    }

    @Test
    fun fingerprintMismatchBuildsUserVisibleDialog() {
        val dialog = buildHttpDeviceConnectionFailureDialogOrNull(
            testDevice(),
            IllegalStateException(AppStrings.ui_service_certificate_fingerprint_does_not_match)
        )

        assertNotNull(dialog)
        assertEquals(AppStrings.ui_device_authentication_failed, dialog.title)
        assertContains(dialog.message, AppStrings.ui_test_device_connection_failure_dialog_testing_equipment)
        assertContains(dialog.message, "127.0.0.1:12040")
        assertContains(dialog.message, AppStrings.ui_service_certificate_fingerprint_does_not_match)
        assertContains(dialog.message, AppStrings.ui_test_device_connection_failure_dialog_re_scan)
    }

    @Test
    fun fingerprintMismatchCanBeDetectedFromNestedCause() {
        val dialog = buildHttpDeviceConnectionFailureDialogOrNull(
            testDevice(),
            IllegalStateException(AppStrings.ui_connection_failed, IllegalArgumentException(AppStrings.ui_service_certificate_fingerprint_does_not_match))
        )

        assertNotNull(dialog)
        assertEquals(AppStrings.ui_device_authentication_failed, dialog.title)
    }

    @Test
    fun genericConnectionFailureDoesNotBuildGlobalDialog() {
        val dialog = buildHttpDeviceConnectionFailureDialogOrNull(
            testDevice(),
            IllegalStateException("Connection timed out")
        )

        assertNull(dialog)
    }

    private fun testDevice(): SocketDevice {
        return SocketDevice(
            id = "device-1",
            name = AppStrings.ui_test_device_connection_failure_dialog_testing_equipment,
            pathSeparator = "/",
            host = "127.0.0.1",
            port = 12040,
            type = DeviceType.JVM,
            httpsPort = 12040,
            tlsFingerprintSha256 = "AA:BB:CC",
        )
    }
}
