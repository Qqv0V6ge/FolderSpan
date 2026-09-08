package com.folderspan.ui.state.device

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.device.DeviceJoinDeviceRole
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备设置状态管理类
 *
 * 负责管理设备相关的状态和数据操作，包括:
 * - 设备列表的维护
 * - 设备分类的更新
 * - 设备名称的更新
 * - 设备信息的刷新
 *
 * @property database 文件管理数据库实例
 */
class DeviceSettingsState(private val database: FolderSpanDatabase) {
    /**
     * 设备列表，包含设备及其角色信息
     * 使用mutableStateListOf以便Compose可以观察变化
     */
    val devices = mutableStateListOf<DeviceJoinDeviceRole>()

    private val _category: MutableStateFlow<DeviceCategory> = MutableStateFlow(DeviceCategory.SERVER)

    /**
     * 当前设备分类的状态流
     * 默认为SERVER类型
     */
    val category: StateFlow<DeviceCategory> = _category

    /**
     * 更新设备分类
     * @param value 新的设备分类值
     */
    fun updateCategory(value: DeviceCategory) {
        _category.value = value
    }

    private val _deviceName: MutableStateFlow<String> = MutableStateFlow("")

    /**
     * 设备名称的状态流
     * 用于设备搜索过滤
     */
    val deviceName: StateFlow<String> = _deviceName

    /**
     * 更新设备名称
     * @param value 新的设备名称
     */
    fun updateDeviceName(value: String) {
        _deviceName.value = value
    }

    /**
     * 刷新设备列表的回调函数
     * 根据当前设备名称和分类从数据库重新加载设备列表
     */
    suspend fun refresh() {
        devices.apply {
            clear()
            addAll(
                database.deviceConnectQueries.queryByNameLikeAndCategory(
                    "%${_deviceName.value}%",
                    _category.value
                ).executeAsListAwait().map { item ->
                    DeviceJoinDeviceRole(
                        id = item.id,
                        name = item.deviceName ?: "",
                        type = item.deviceType ?: DeviceType.JVM,
                        connectionType = item.connectionType,
                        firstConnection = item.firstConnection,
                        lastConnection = item.lastConnection,
                        category = item.category,
                        roleId = item.roleId,
                        roleName = item.roleName ?: ""
                    )
                }
            )
        }
    }

    suspend fun upsertDeviceAccess(
        deviceId: String,
        deviceName: String,
        category: DeviceCategory,
        connectionType: DeviceConnectType,
        roleId: Long? = null,
        refreshAfter: Boolean = true
    ) {
        database.deviceQueries.updateNameAndEnableRemarksById(
            deviceName,
            deviceId
        ).awaitDatabaseReady()

        val existingDevice = database.deviceConnectQueries.queryByIdAndCategory(
            deviceId,
            category
        ).executeAsOneOrNullAwait()

        if (existingDevice == null) {
            database.deviceConnectQueries.upsert(
                id = deviceId,
                connectionType = connectionType,
                category = category,
                roleId = roleId
            ).awaitDatabaseReady()
        } else {
            database.deviceConnectQueries.updateNameConnectTypeRoleIdByIdAndCategory(
                connectionType,
                roleId,
                deviceId,
                category
            ).awaitDatabaseReady()
        }

        if (refreshAfter) {
            refresh()
        }
        SyncSnapshotChangeNotifier.onDeviceConfigurationChanged()
    }

}
