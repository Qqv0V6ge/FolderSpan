package com.folderspan.ui.state.device

import androidx.compose.runtime.mutableStateListOf
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.SqlDriver
import com.folderspan.data.device.DeviceRole
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceRoleState(private val database: FolderSpanDatabase, driver: SqlDriver) {
    val roles = mutableStateListOf<DeviceRole>()
    private val scope = MainScope()
    private val transactions = object : SuspendingTransacterImpl(driver) {}

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
            DatabaseReady.await()
            transactions.transaction {
                database.deviceRoleDevicePermissionQueries.deleteByRoleId(deviceRole.id).await()
                database.deviceRoleQueries.deleteRoleById(deviceRole.id).await()
            }
        }
        withContext(Dispatchers.Main) {
            if (index in roles.indices) {
                roles.removeAt(index)
            }
        }
        SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
    }
}
