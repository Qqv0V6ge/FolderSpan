package com.folderspan.permission

import com.folderspan.notification.JsNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import strings.AppStrings
import kotlin.js.Promise

actual object PlatformPermissionProvider {
    actual fun permissions(): List<PlatformPermission> = buildList {
        if (isNotificationSupported()) {
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
        if (isLocalNetworkQuerySupported()) {
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
        if (isFilePickerSupported()) {
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
        try {
            JsNotification.requestPermission().then(
                onFulfilled = { result -> onResult(result.toPermissionStatus()) },
                onRejected = { onResult(PermissionStatus.Unknown) },
            )
        } catch (_: Throwable) {
            onResult(PermissionStatus.Unknown)
        }
    }

    actual fun openSettings(permission: PlatformPermission) {
        // Browser settings are privileged. The shared permission page displays settingsInstructions.
    }
}

private fun isNotificationSupported(): Boolean = js("""
    globalThis.isSecureContext === true && typeof Notification === 'function' &&
        typeof Notification.requestPermission === 'function'
""") as Boolean

private fun isLocalNetworkQuerySupported(): Boolean = js("""
    globalThis.isSecureContext === true && typeof navigator !== 'undefined' &&
        navigator.permissions != null && typeof navigator.permissions.query === 'function'
""") as Boolean

private fun isFilePickerSupported(): Boolean = js("""
    globalThis.isSecureContext === true && typeof globalThis.showSaveFilePicker === 'function'
""") as Boolean

private fun notificationStatus(): PermissionStatus =
    if (isNotificationSupported()) {
        runCatching { JsNotification.permission.toPermissionStatus() }.getOrDefault(PermissionStatus.Unknown)
    } else {
        PermissionStatus.Unsupported
    }

private suspend fun localNetworkStatus(): PermissionStatus {
    if (!isLocalNetworkQuerySupported()) return PermissionStatus.Unsupported
    return withTimeoutOrNull(3_000) {
        try {
            // Query only the granular descriptor. The old combined alias can crash older Chromium.
            val query = js("navigator.permissions.query({ name: 'local-network' })") as Promise<dynamic>
            val state = query.await().state as? String
            state.toPermissionStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.asDynamic().name == "TypeError") PermissionStatus.Unsupported else PermissionStatus.Unknown
        }
    } ?: PermissionStatus.Unknown
}

private fun fileAccessStatus(): PermissionStatus =
    if (isFilePickerSupported()) PermissionStatus.Unknown else PermissionStatus.Unsupported

private fun String?.toPermissionStatus(): PermissionStatus = when (this) {
    "granted" -> PermissionStatus.Granted
    "denied" -> PermissionStatus.Denied
    "default", "prompt" -> PermissionStatus.NotDetermined
    else -> PermissionStatus.Unknown
}
