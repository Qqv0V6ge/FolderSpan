package com.folderspan.ui.state.device

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.db.DevicePermission
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevicePermissionState(private val database: FolderSpanDatabase) {
    val permissions = mutableStateListOf<DevicePermission>()
    private val scope = MainScope()

    init {
        scope.launch {
            refresh()
        }
    }

    suspend fun refresh() {
        val loadedPermissions = withContext(Dispatchers.Default) {
            database.devicePermissionQueries.select().executeAsListAwait()
        }
        withContext(Dispatchers.Main) {
            permissions.clear()
            permissions.addAll(loadedPermissions)
        }
    }

    suspend fun delete(permission: DevicePermission, index: Int) {
        withContext(Dispatchers.Default) {
            database.devicePermissionQueries.deleteById(permission.id).awaitDatabaseReady()
        }
        withContext(Dispatchers.Main) {
            if (index in permissions.indices) {
                permissions.removeAt(index)
            }
        }
        SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
    }

    suspend fun update(permission: DevicePermission, index: Int) {
        withContext(Dispatchers.Default) {
            database.devicePermissionQueries.updateById(
                path = permission.path,
                useAll = permission.useAll,
                read = permission.read,
                write = permission.write,
                remove = permission.remove,
                rename = permission.rename,
                sortOrder = permission.sortOrder,
                comment = permission.comment,
                id = permission.id
            ).awaitDatabaseReady()
        }
        withContext(Dispatchers.Main) {
            if (index in permissions.indices) {
                permissions[index] = permission
            }
        }
        SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
    }

    suspend fun create(path: String, comment: String?) {
        val createdPermission = withContext(Dispatchers.Default) {
            database.devicePermissionQueries.insert(path, comment).awaitDatabaseReady()
            database.devicePermissionQueries.lastInsertRowId().executeAsOneOrNullAwait()?.let { id ->
                database.devicePermissionQueries.selectById(id).executeAsOneOrNullAwait()
            }
        }
        if (createdPermission != null) {
            withContext(Dispatchers.Main) {
                permissions.add(createdPermission)
            }
            SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
        }
    }
}
