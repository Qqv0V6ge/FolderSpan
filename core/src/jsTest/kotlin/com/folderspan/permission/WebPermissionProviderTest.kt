package com.folderspan.permission

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebPermissionProviderTest {
    @AfterTest
    fun restoreBrowserApis() {
        js("if (typeof globalThis.__folderSpanRestorePermissionTest === 'function') globalThis.__folderSpanRestorePermissionTest()")
    }

    @Test
    fun insecureContextsDoNotOfferNotificationAuthorization() {
        installNotificationApi(secure = false)

        assertFalse(PlatformPermissionProvider.permissions().any { it.id == PermissionIds.Notifications })
    }

    @Test
    fun notificationDenialCanBeDirectedToSiteSettings() {
        installNotificationApi(permission = "denied")

        assertEquals(PermissionAction.RequestOnce, notificationPermission().action)
    }

    @Test
    fun notificationStatusPreservesBrowserAuthorizationState() = runSuspendTest {
        installNotificationApi(permission = "denied")

        assertEquals(PermissionStatus.Denied, PlatformPermissionProvider.status(notificationPermission()))
    }

    @Test
    fun notificationRequestFailureDoesNotClaimUserDenial() = runSuspendTest {
        installNotificationApi(rejectRequest = true)

        val status = suspendCoroutine { continuation ->
            PlatformPermissionProvider.request(notificationPermission()) { continuation.resume(it) }
        }

        assertEquals(PermissionStatus.Unknown, status)
    }

    @Test
    fun unrecognizedBrowserStatusDoesNotClaimApiIsUnsupported() = runSuspendTest {
        installNotificationApi(permission = "unexpected")

        assertEquals(PermissionStatus.Unknown, PlatformPermissionProvider.status(notificationPermission()))
    }

    @Test
    fun localNetworkStateUsesSupportedGranularDescriptor() = runSuspendTest {
        installNotificationApi(localNetworkState = "granted")

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        assertEquals(PermissionStatus.Granted, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun localNetworkPromptDoesNotPretendAuthorizationWasDenied() = runSuspendTest {
        installNotificationApi(localNetworkState = "prompt")

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        assertEquals(PermissionStatus.NotDetermined, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun unsupportedLocalNetworkDescriptorIsReportedWithoutFallbackToLegacyName() = runSuspendTest {
        installNotificationApi(localNetworkState = "unsupported")

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        assertEquals(PermissionStatus.Unsupported, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun insecureContextsDoNotOfferLocalNetworkAuthorization() {
        installNotificationApi(secure = false)

        assertFalse(PlatformPermissionProvider.permissions().any { it.id == PermissionIds.LocalNetwork })
    }

    @Test
    fun fileAccessExplainsPerFileAuthorizationWithoutOpeningAPicker() = runSuspendTest {
        installNotificationApi(filePickerSupported = true)

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.FilesAndFolders }
        assertEquals(PermissionStatus.Unknown, PlatformPermissionProvider.status(permission))
        assertEquals(0, filePickerRequestCount())
    }

    @Test
    fun browsersWithoutFileSystemAccessDoNotOfferAnUnusablePickerPermission() {
        installNotificationApi(filePickerSupported = false)

        assertFalse(PlatformPermissionProvider.permissions().any { it.id == PermissionIds.FilesAndFolders })
    }

    @Test
    fun notificationRequestInvokesBrowserApiBeforeReturningToPreserveUserActivation() = runSuspendTest {
        installNotificationApi()
        val result = CompletableDeferred<PermissionStatus>()

        PlatformPermissionProvider.request(notificationPermission()) { result.complete(it) }

        assertEquals(1, notificationRequestCount())
        assertEquals(PermissionStatus.Granted, result.await())
    }

    @Test
    fun localNetworkDenialPreservesTheBrowsersDecision() = runSuspendTest {
        installNotificationApi(localNetworkState = "denied")

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        assertEquals(PermissionStatus.Denied, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun localNetworkQueryFailureDoesNotClaimUserDenialOrMissingApi() = runSuspendTest {
        installNotificationApi(localNetworkState = "error")

        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        assertEquals(PermissionStatus.Unknown, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun unansweredLocalNetworkQueriesTimeOutAfterThreeSecondsAndIgnoreLateGrant() = runTest {
        installNotificationApi(localNetworkState = "pending")
        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        val result = async { PlatformPermissionProvider.status(permission) }
        var completions = 0
        result.invokeOnCompletion { completions++ }

        runCurrent()
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PermissionStatus.Unknown, result.await())

        resolvePendingLocalNetworkQuery("granted")
        flushPermissionMicrotasks()
        runCurrent()

        assertEquals(PermissionStatus.Unknown, result.await())
        assertEquals(1, completions)
    }

    @Test
    fun cancelledLocalNetworkQueriesIgnoreLateGrant() = runTest {
        installNotificationApi(localNetworkState = "pending")
        val permission = PlatformPermissionProvider.permissions().single { it.id == PermissionIds.LocalNetwork }
        val result = async { PlatformPermissionProvider.status(permission) }
        var completions = 0
        result.invokeOnCompletion { completions++ }

        runCurrent()
        result.cancelAndJoin()
        resolvePendingLocalNetworkQuery("granted")
        flushPermissionMicrotasks()
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(1, completions)
    }

    private fun notificationPermission(): PlatformPermission =
        PlatformPermissionProvider.permissions().single { it.id == PermissionIds.Notifications }
}

private fun installNotificationApi(
    secure: Boolean = true,
    permission: String = "default",
    rejectRequest: Boolean = false,
    localNetworkState: String = "prompt",
    filePickerSupported: Boolean = false,
) {
    js("""
        if (typeof globalThis.__folderSpanRestorePermissionTest === 'function') {
            globalThis.__folderSpanRestorePermissionTest();
        }
        var notificationDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Notification');
        var secureDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'isSecureContext');
        var permissionsDescriptor = Object.getOwnPropertyDescriptor(navigator, 'permissions');
        var pickerDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'showSaveFilePicker');
        var counters = { notificationRequests: 0, filePickerRequests: 0 };
        globalThis.__folderSpanPermissionTestCounters = counters;
        var testNotification = function() {};
        testNotification.permission = permission;
        testNotification.requestPermission = function() {
            counters.notificationRequests++;
            return rejectRequest
                ? Promise.reject(new Error('Notification service unavailable'))
                : Promise.resolve('granted');
        };
        Object.defineProperty(globalThis, 'showSaveFilePicker', { configurable: true, value: filePickerSupported
            ? function() {
                counters.filePickerRequests++;
                throw new Error('Checking permissions must not open a save picker');
            }
            : undefined });
        Object.defineProperty(navigator, 'permissions', { configurable: true, value: {
            query: function(descriptor) {
                if (descriptor.name !== 'local-network' || localNetworkState === 'unsupported') {
                    return Promise.reject(new TypeError('Unsupported permission descriptor'));
                }
                if (localNetworkState === 'error') {
                    return Promise.reject(new Error('Permission service unavailable'));
                }
                if (localNetworkState === 'pending') {
                    return new Promise(function(resolve) {
                        globalThis.__folderSpanResolvePermissionTest = resolve;
                    });
                }
                return Promise.resolve({ state: localNetworkState });
            }
        }});
        Object.defineProperty(globalThis, 'Notification', { configurable: true, value: testNotification });
        Object.defineProperty(globalThis, 'isSecureContext', { configurable: true, value: secure });
        globalThis.__folderSpanRestorePermissionTest = function() {
            if (notificationDescriptor) Object.defineProperty(globalThis, 'Notification', notificationDescriptor);
            else delete globalThis.Notification;
            if (secureDescriptor) Object.defineProperty(globalThis, 'isSecureContext', secureDescriptor);
            else delete globalThis.isSecureContext;
            if (permissionsDescriptor) Object.defineProperty(navigator, 'permissions', permissionsDescriptor);
            else delete navigator.permissions;
            if (pickerDescriptor) Object.defineProperty(globalThis, 'showSaveFilePicker', pickerDescriptor);
            else delete globalThis.showSaveFilePicker;
            delete globalThis.__folderSpanPermissionTestCounters;
            delete globalThis.__folderSpanResolvePermissionTest;
            delete globalThis.__folderSpanRestorePermissionTest;
        };
    """)
}

private fun notificationRequestCount(): Int =
    js("globalThis.__folderSpanPermissionTestCounters.notificationRequests") as Int

private fun filePickerRequestCount(): Int =
    js("globalThis.__folderSpanPermissionTestCounters.filePickerRequests") as Int

private fun resolvePendingLocalNetworkQuery(state: String) {
    js("globalThis.__folderSpanResolvePermissionTest({ state: state })")
}

private suspend fun flushPermissionMicrotasks() {
    suspendCoroutine<Unit> { continuation ->
        val completeMicrotask = { continuation.resume(Unit) }
        js("Promise.resolve().then(completeMicrotask)")
    }
}
