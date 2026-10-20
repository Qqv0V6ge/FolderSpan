package com.folderspan.permission

import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.AuthorizationStatus
import dev.nucleusframework.notification.NotificationCenter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface MacNotificationAuthorization {
    val isAvailable: Boolean
    fun status(onResult: (PermissionStatus) -> Unit)
    fun request(onResult: (PermissionStatus) -> Unit)
}

internal class MacNotificationPermission(
    private val authorization: MacNotificationAuthorization = NativeMacNotificationAuthorization,
) {
    suspend fun status(): PermissionStatus {
        if (!available()) return PermissionStatus.Unsupported
        return withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    authorization.status { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(PermissionStatus.Unknown)
                }
            }
        } ?: PermissionStatus.Unknown
    }

    fun request(onResult: (PermissionStatus) -> Unit) {
        if (!available()) {
            onResult(PermissionStatus.Unsupported)
            return
        }
        runCatching { authorization.request(onResult) }
            .onFailure { onResult(PermissionStatus.Unknown) }
    }

    private fun available(): Boolean = runCatching { authorization.isAvailable }.getOrDefault(false)
}

private object NativeMacNotificationAuthorization : MacNotificationAuthorization {
    override val isAvailable: Boolean
        get() = NotificationCenter.isAvailable

    override fun status(onResult: (PermissionStatus) -> Unit) {
        NotificationCenter.getNotificationSettings { settings ->
            onResult(settings.authorizationStatus.toPermissionStatus())
        }
    }

    override fun request(onResult: (PermissionStatus) -> Unit) {
        NotificationCenter.requestAuthorization(
            setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE)
        ) { granted, error ->
            onResult(
                when {
                    error != null -> PermissionStatus.Unknown
                    granted -> PermissionStatus.Granted
                    else -> PermissionStatus.Denied
                }
            )
        }
    }
}

internal fun AuthorizationStatus.toPermissionStatus(): PermissionStatus = when (this) {
    AuthorizationStatus.NOT_DETERMINED -> PermissionStatus.NotDetermined
    AuthorizationStatus.DENIED -> PermissionStatus.Denied
    AuthorizationStatus.AUTHORIZED, AuthorizationStatus.PROVISIONAL, AuthorizationStatus.EPHEMERAL -> PermissionStatus.Granted
}
