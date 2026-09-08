package com.folderspan.shizuku

/** 用户态服务绑定的生命周期快照，便于 UI 快速判断当前状态。 */
sealed class ShizukuServiceState {
    data object Idle : ShizukuServiceState()
    data object Binding : ShizukuServiceState()
    data object Bound : ShizukuServiceState()
    data object Disconnected : ShizukuServiceState()
    data class Failed(val error: Throwable?) : ShizukuServiceState()
}

/** 通过 [ShizukuManager.permissionState] 暴露的权限状态枚举。 */
enum class ShizukuPermissionState {
    Unsupported,
    ServiceMissing,
    Denied,
    Granted
}

/** 单次权限请求的返回结果，用于区分 Grant、拒绝或缺少服务等情况。 */
enum class ShizukuPermissionResult {
    Granted,
    Denied,
    Cancelled,
    ServiceMissing
}
