package com.folderspan.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PermissionSettingsStatusState(
    private val readStatus: suspend (PlatformPermission) -> PermissionStatus,
) {
    private val statusMap = mutableStateMapOf<String, PermissionStatus>()
    private val resultVersions = mutableMapOf<String, Int>()
    private val refreshMutex = Mutex()
    val statuses: Map<String, PermissionStatus> get() = statusMap
    var refreshing by mutableStateOf(false)
        private set

    suspend fun refresh(permissions: List<PlatformPermission>) = refreshMutex.withLock {
        refreshing = true
        try {
            permissions.forEach { permission ->
                val version = resultVersions[permission.id] ?: 0
                val status = try {
                    readStatus(permission)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PermissionStatus.Unknown
                }
                // An explicit authorization result is newer than an in-flight read.
                if ((resultVersions[permission.id] ?: 0) == version) {
                    statusMap[permission.id] = status
                }
            }
        } finally {
            refreshing = false
        }
    }

    fun onRequestResult(id: String, status: PermissionStatus) {
        resultVersions[id] = (resultVersions[id] ?: 0) + 1
        statusMap[id] = status
    }
}
