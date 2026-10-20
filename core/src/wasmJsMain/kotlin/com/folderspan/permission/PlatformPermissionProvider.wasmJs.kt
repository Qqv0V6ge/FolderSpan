@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.permission

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import strings.AppStrings
import kotlin.coroutines.resume

actual object PlatformPermissionProvider {
    actual fun permissions(): List<PlatformPermission> = buildList {
        if (isNotificationSupportedJs()) {
            add(
                PlatformPermission(
                    PermissionIds.Notifications,
                    PermissionAction.RequestOnce,
                    title = AppStrings.permission_notifications_title,
                    description = AppStrings.permission_web_notifications_description,
                    settingsInstructions = AppStrings.permission_web_site_settings_instructions,
                )
            )
        }
        if (isLocalNetworkQuerySupportedJs()) {
            add(
                PlatformPermission(
                    PermissionIds.LocalNetwork,
                    PermissionAction.ShowInstructions,
                    title = AppStrings.permission_local_network_title,
                    description = AppStrings.permission_web_local_network_description,
                    settingsInstructions = AppStrings.permission_web_site_settings_instructions,
                )
            )
        }
        if (isFilePickerSupportedJs()) {
            add(
                PlatformPermission(
                    PermissionIds.FilesAndFolders,
                    PermissionAction.ShowInstructions,
                    title = AppStrings.permission_files_and_folders_title,
                    description = AppStrings.permission_web_file_save_description,
                )
            )
        }
    }

    actual suspend fun status(permission: PlatformPermission): PermissionStatus = when (permission.id) {
        PermissionIds.Notifications -> notificationStatus()
        PermissionIds.LocalNetwork -> localNetworkStatus()
        PermissionIds.FilesAndFolders -> fileAccessStatus()
        else -> PermissionStatus.Unsupported
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        if (permission.id != PermissionIds.Notifications) {
            onResult(if (permission.id == PermissionIds.FilesAndFolders) fileAccessStatus() else PermissionStatus.Unsupported)
            return
        }
        val currentStatus = notificationStatus()
        if (currentStatus != PermissionStatus.NotDetermined) {
            onResult(currentStatus)
            return
        }
        // Keep the native request on the user's click stack; awaiting first can lose user activation.
        requestNotificationPermission { result -> onResult(result.toPermissionStatus()) }
    }

    actual fun openSettings(permission: PlatformPermission) {
        // Browser settings are privileged. The shared permission page displays settingsInstructions.
    }
}

@JsFun("""() => globalThis.isSecureContext === true && typeof Notification === 'function' &&
    typeof Notification.requestPermission === 'function'""")
private external fun isNotificationSupportedJs(): Boolean

@JsFun("""() => globalThis.isSecureContext === true && typeof navigator !== 'undefined' &&
    navigator.permissions != null && typeof navigator.permissions.query === 'function'""")
private external fun isLocalNetworkQuerySupportedJs(): Boolean

@JsFun("() => globalThis.isSecureContext === true && typeof globalThis.showSaveFilePicker === 'function'")
private external fun isFilePickerSupportedJs(): Boolean

private fun notificationStatus(): PermissionStatus =
    if (isNotificationSupportedJs()) notificationPermissionJs().toPermissionStatus() else PermissionStatus.Unsupported

@JsFun("""() => {
    try { return Notification.permission; } catch (_) { return 'unknown'; }
}""")
private external fun notificationPermissionJs(): String

@JsFun(
    """
    (onResult) => {
        try {
            Notification.requestPermission().then(onResult, () => onResult('unknown'));
        } catch (_) {
            onResult('unknown');
        }
    }
    """
)
private external fun requestNotificationPermission(onResult: (String) -> Unit)

private suspend fun localNetworkStatus(): PermissionStatus {
    if (!isLocalNetworkQuerySupportedJs()) return PermissionStatus.Unsupported
    return withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { continuation ->
            queryLocalNetworkPermission { result ->
                if (continuation.isActive) {
                    continuation.resume(
                        if (result == "unsupported") PermissionStatus.Unsupported else result.toPermissionStatus()
                    )
                }
            }
        }
    } ?: PermissionStatus.Unknown
}

// Query only the granular descriptor. The old combined alias can crash older Chromium.
@JsFun(
    """
    (onResult) => {
        const onError = (error) => onResult(error && error.name === 'TypeError' ? 'unsupported' : 'unknown');
        try {
            navigator.permissions.query({ name: 'local-network' }).then(
                (permission) => onResult(permission.state),
                onError
            );
        } catch (error) {
            onError(error);
        }
    }
    """
)
private external fun queryLocalNetworkPermission(onResult: (String) -> Unit)

private fun fileAccessStatus(): PermissionStatus =
    if (isFilePickerSupportedJs()) PermissionStatus.Unknown else PermissionStatus.Unsupported

private fun String.toPermissionStatus(): PermissionStatus = when (this) {
    "granted" -> PermissionStatus.Granted
    "denied" -> PermissionStatus.Denied
    "default", "prompt" -> PermissionStatus.NotDetermined
    else -> PermissionStatus.Unknown
}
