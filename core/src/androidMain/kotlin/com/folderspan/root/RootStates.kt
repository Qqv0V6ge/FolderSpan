package com.folderspan.root

import com.folderspan.permission.PermissionStatus

sealed class RootPermissionState {
    data object Unsupported : RootPermissionState()
    data object NotDetermined : RootPermissionState()
    data object Requesting : RootPermissionState()
    data object Denied : RootPermissionState()
    data object Granted : RootPermissionState()
    data class Failed(val error: Throwable?) : RootPermissionState()
}

sealed class RootServiceState {
    data object Idle : RootServiceState()
    data object Binding : RootServiceState()
    data object Bound : RootServiceState()
    data object Disconnected : RootServiceState()
    data class Failed(val error: Throwable?) : RootServiceState()
}

enum class RootPermissionResult {
    Granted,
    Denied,
    Unavailable
}

fun RootPermissionState.toPermissionStatus(): PermissionStatus = when (this) {
    RootPermissionState.Unsupported -> PermissionStatus.Unsupported
    RootPermissionState.NotDetermined -> PermissionStatus.NotDetermined
    RootPermissionState.Requesting -> PermissionStatus.NotDetermined
    RootPermissionState.Denied -> PermissionStatus.Denied
    RootPermissionState.Granted -> PermissionStatus.Granted
    is RootPermissionState.Failed -> PermissionStatus.Denied
}
