package com.folderspan.service.http.server

import strings.AppStrings

import io.github.skeptick.libres.LibresSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HttpServerStartErrorTest {
    @AfterTest
    fun resetLanguage() {
        LibresSettings.languageCode = "en"
    }

    @Test
    fun returnsFixedPortOccupiedMessage() {
        LibresSettings.languageCode = "zhHans"
        val result = portInUseMessage(1204)

        assertEquals(AppStrings.ui_test_http_server_start_error_port_1204_is_already_occupied_please_change, result)
    }

    @Test
    fun returnsGenericMessageWithDetailWhenNoBindHint() {
        LibresSettings.languageCode = "zhHans"
        val error = RuntimeException("network init timeout")

        val result = error.toServerStartFailureMessage(1204)

        assertContains(result, AppStrings.ui_test_http_server_start_error_service_started_failed)
        assertContains(result, "network init timeout")
    }

    @Test
    fun returnsPortOccupiedMessageForBindFailure() {
        LibresSettings.languageCode = "zhHans"
        val error = RuntimeException("java.net.BindException: Address already in use")

        val result = error.toServerStartFailureMessage(1204)

        assertEquals(AppStrings.ui_test_http_server_start_error_port_1204_is_already_occupied_please_change, result)
    }

    @Test
    fun rendersServerStartMessagesInEnglish() {
        LibresSettings.languageCode = "en"

        assertEquals(
            "Port 1204 is already in use. Change the port in Settings and try again, or close the app using it.",
            portInUseMessage(1204),
        )
        assertEquals(
            "The service failed to start: network init timeout",
            RuntimeException("network init timeout").toServerStartFailureMessage(1204),
        )
    }
}
