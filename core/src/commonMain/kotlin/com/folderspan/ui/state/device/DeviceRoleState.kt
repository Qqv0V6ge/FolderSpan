package com.folderspan.ui.state.device

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.device.DeviceRole
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceRoleState(private val database: FolderSpanDatabase) {
    val roles = mutableStateListOf<DeviceRole>()
    private val scope = MainScope()

    init {
        scope.launch {
            refresh()
        }
    }

    suspend fun refresh() {
        val map = withContext(Dispatchers.Default) {
            database.deviceRoleQueries.selectAll().executeAsListAwait().map { item ->
                DeviceRole(
                    id = item.id,
                    name = item.name,
                    comment = item.comment,
                    sortOrder = item.sortOrder,
                    permissionCount = item.roleCount
                )
            }
        }
        if (roles.toList() != map) {
            roles.clear()
            roles.addAll(map)
        }
    }

    suspend fun delete(deviceRole: DeviceRole, index: Int) {
        withContext(Dispatchers.Default) {
            database.devicePermissionQueries.deleteById(deviceRole.id).awaitDatabaseReady()
        }
        withContext(Dispatchers.Main) {
            if (index in roles.indices) {
                roles.removeAt(index)
            }
        }
        SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
    }
}
