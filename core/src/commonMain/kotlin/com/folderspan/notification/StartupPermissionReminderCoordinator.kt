package com.folderspan.notification

import com.folderspan.permission.PermissionAction
import com.folderspan.permission.PermissionIds
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermission
import com.folderspan.permission.PlatformPermissionProvider
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.SettingsUtils

object StartupPermissionReminderCoordinator {
    private var lastMissingSignature: String? = null
    private var handledFallback = false

    suspend fun evaluateAndNotify(notificationState: NotificationState) {
        if (isReminderHandled()) {
            return
        }
        val missingPermissions = resolveMissingPermissions()
        val currentSignature = missingPermissionSignature(missingPermissions.map { item -> item.id })

        if (currentSignature.isEmpty()) {
            lastMissingSignature = null
            return
        }
        if (!shouldPostReminder(lastMissingSignature, currentSignature)) {
            return
        }
        lastMissingSignature = currentSignature

        val sendSystemNotification = missingPermissions.none { item -> item.id == PermissionIds.Notifications }
        val bundle = RequestNotificationFactory.buildPermissionReminderNotification(
            missingPermissions = missingPermissions,
            config = NotificationFactoryConfig(
                showInBell = true,
                showInBanner = false,
                sendSystemNotification = sendSystemNotification
            )
        )
        RequestNotificationDispatcher.post(notificationState, bundle)
    }

    fun markReminderHandled() {
        handledFallback = true
        runCatching {
            SettingsUtils.setStartupPermissionReminderHandled(true)
        }
    }

    internal fun shouldPostReminder(previousSignature: String?, currentSignature: String): Boolean {
        return currentSignature.isNotBlank() && previousSignature != currentSignature
    }

    internal fun missingPermissionSignature(permissionIds: List<String>): String {
        return permissionIds
            .asSequence()
            .map { item -> item.trim() }
            .filter { item -> item.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    private suspend fun resolveMissingPermissions(): List<PlatformPermission> {
        val permissions = PlatformPermissionProvider.permissions()
        if (permissions.isEmpty()) {
            return emptyList()
        }

        return permissions.filter { permission ->
            if (permission.action == PermissionAction.None) {
                return@filter false
            }
            val status = runCatching { PlatformPermissionProvider.status(permission) }
                .getOrElse { PermissionStatus.Unsupported }
            status == PermissionStatus.Denied || status == PermissionStatus.NotDetermined
        }
    }

    private fun isReminderHandled(): Boolean {
        return runCatching {
            SettingsUtils.isStartupPermissionReminderHandled()
        }.getOrElse { handledFallback }
    }
}
