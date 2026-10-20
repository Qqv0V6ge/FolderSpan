package com.folderspan.permission

import dev.nucleusframework.notification.AuthorizationStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MacNotificationPermissionTest {
    @Test
    fun checkingStatusNeverRequestsAuthorizationAndSeesSystemSettingsChanges() = runTest {
        val native = FakeMacNotificationAuthorization()
        val permission = MacNotificationPermission(native)

        assertEquals(PermissionStatus.NotDetermined, permission.status())
        native.result = PermissionStatus.Denied
        assertEquals(PermissionStatus.Denied, permission.status())
        native.result = PermissionStatus.Granted
        assertEquals(PermissionStatus.Granted, permission.status())
        assertEquals(0, native.requests)
    }

    @Test
    fun explicitRequestReturnsTheNativeResult() {
        val native = FakeMacNotificationAuthorization().apply { result = PermissionStatus.Granted }
        var result: PermissionStatus? = null

        MacNotificationPermission(native).request { result = it }

        assertEquals(1, native.requests)
        assertEquals(PermissionStatus.Granted, result)
    }

    @Test
    fun unpackagedRuntimeDoesNotCallAnUnavailableNativeApi() = runTest {
        val native = FakeMacNotificationAuthorization().apply { isAvailable = false }
        val permission = MacNotificationPermission(native)
        var requested: PermissionStatus? = null

        assertEquals(PermissionStatus.Unsupported, permission.status())
        permission.request { requested = it }

        assertEquals(PermissionStatus.Unsupported, requested)
        assertEquals(0, native.reads)
        assertEquals(0, native.requests)
    }

    @Test
    fun missingNativeCallbackTimesOutWithoutReportingDenial() = runTest {
        val native = FakeMacNotificationAuthorization().apply { respondToStatus = false }
        assertEquals(PermissionStatus.Unknown, MacNotificationPermission(native).status())
    }

    @Test
    fun nativeAuthorizationStatesMapToTheirActualPermissionState() {
        assertEquals(PermissionStatus.NotDetermined, AuthorizationStatus.NOT_DETERMINED.toPermissionStatus())
        assertEquals(PermissionStatus.Denied, AuthorizationStatus.DENIED.toPermissionStatus())
        listOf(AuthorizationStatus.AUTHORIZED, AuthorizationStatus.PROVISIONAL, AuthorizationStatus.EPHEMERAL)
            .forEach { assertEquals(PermissionStatus.Granted, it.toPermissionStatus()) }
    }
}

private class FakeMacNotificationAuthorization : MacNotificationAuthorization {
    override var isAvailable = true
    var result = PermissionStatus.NotDetermined
    var respondToStatus = true
    var reads = 0
    var requests = 0

    override fun status(onResult: (PermissionStatus) -> Unit) {
        reads++
        if (respondToStatus) onResult(result)
    }

    override fun request(onResult: (PermissionStatus) -> Unit) {
        requests++
        onResult(result)
    }
}
