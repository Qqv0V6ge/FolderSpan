package com.folderspan.permission

import kotlin.test.Test
import kotlin.test.assertEquals

class IosLocalNetworkPermissionTest {
    @Test
    fun statusChecksNeverTriggerTheSystemPrivacyPrompt() {
        var attempts = 0
        val permission = IosLocalNetworkPermission(majorVersion = 14, trigger = { attempts++ })

        repeat(3) { assertEquals(PermissionStatus.Unknown, permission.status()) }

        assertEquals(0, attempts)
    }

    @Test
    fun anExplicitRequestAttemptsThePromptAndKeepsTheStateUnknown() {
        var attempts = 0
        val permission = IosLocalNetworkPermission(majorVersion = 26, trigger = { attempts++ })

        assertEquals(PermissionStatus.Unknown, permission.request())
        assertEquals(1, attempts)
    }

    @Test
    fun networkingFailuresDoNotMeanTheUserDeniedPermission() {
        val permission = IosLocalNetworkPermission(majorVersion = 14, trigger = { error("No usable interface") })

        assertEquals(PermissionStatus.Unknown, permission.request())
    }

    @Test
    fun olderIosVersionsDoNotAttemptToRequestAnUnavailablePermission() {
        var attempts = 0
        val permission = IosLocalNetworkPermission(majorVersion = 13, trigger = { attempts++ })

        assertEquals(PermissionStatus.Unsupported, permission.status())
        assertEquals(PermissionStatus.Unsupported, permission.request())
        assertEquals(0, attempts)
    }
}
