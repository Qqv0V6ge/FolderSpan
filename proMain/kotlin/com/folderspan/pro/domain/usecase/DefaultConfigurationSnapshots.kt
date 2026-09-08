package com.folderspan.pro.domain.usecase

import com.folderspan.db.FolderSpanDatabase
import com.folderspan.pro.domain.model.*
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.device.DeviceSettingsState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.SyncState
import com.folderspan.ui.state.main.SyncTaskStore
import com.folderspan.ui.state.main.WebRtcRoomState
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.executeAsListAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

class DefaultConfigurationSnapshots(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : KoinComponent {
    private val database by inject<FolderSpanDatabase>()
    private val syncTaskStore by inject<SyncTaskStore>()

    val providers: List<ConfigurationSnapshotProvider> by lazy {
        listOf(
            DeviceConfigurationSnapshotProvider(database, json),
            RoleConfigurationSnapshotProvider(database, json),
            NetworkDrivesConfigurationSnapshotProvider(database, json),
            WebRtcRoomsConfigurationSnapshotProvider(database, json),
            SyncTasksConfigurationSnapshotProvider(syncTaskStore, json),
            DataEncryptionKeySnapshotProvider(),
        )
    }
}

class DeviceConfigurationSnapshotProvider(
    private val database: FolderSpanDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationSnapshotProvider, KoinComponent {
    override val key: String = ProConfigurationSnapshotKeys.DEVICES

    override suspend fun read(): JsonElement = withContext(Dispatchers.Default) {
        json.encodeToJsonElement(
            DeviceConfigurationSnapshot(
                devices = database.deviceQueries.queryAll().executeAsListAwait().map { item ->
                    DeviceSnapshotItem(
                        id = item.id,
                        name = item.name,
                        host = item.host.orEmpty(),
                        port = item.port ?: 0L,
                        type = item.type,
                        hasRemarks = item.hasRemarks ?: false,
                    )
                },
                connections = database.deviceConnectQueries.queryAll().executeAsListAwait().map { item ->
                    DeviceConnectionSnapshotItem(
                        id = item.id,
                        connectionType = item.connectionType,
                        firstConnection = item.firstConnection,
                        lastConnection = item.lastConnection,
                        category = item.category,
                        roleId = item.roleId,
                    )
                },
                receiveShares = database.deviceReceiveShareQueries.selectAll().executeAsListAwait().map { item ->
                    DeviceReceiveShareSnapshotItem(
                        id = item.id,
                        connectionType = item.connectionType,
                        path = item.path,
                    )
                },
            ),
        )
    }

    override suspend fun apply(value: JsonElement) {
        val snapshot = runCatching { json.decodeFromJsonElement<DeviceConfigurationSnapshot>(value) }.getOrNull() ?: return
        withContext(Dispatchers.Default) {
            database.transaction {
                database.deviceReceiveShareQueries.selectAll().executeAsList().forEach { item ->
                    database.deviceReceiveShareQueries.deleteById(item.id)
                }
                database.deviceConnectQueries.queryAll().executeAsList().forEach { item ->
                    database.deviceConnectQueries.deleteById(item.id)
                }
                database.deviceQueries.queryAll().executeAsList().forEach { item ->
                    database.deviceQueries.deleteById(item.id)
                }
                snapshot.devices.forEach { item ->
                    database.deviceQueries.insert(
                        id = item.id,
                        name = item.name,
                        host = item.host,
                        port = item.port,
                        type = item.type,
                    )
                    database.deviceQueries.updateHasRemarksById(item.hasRemarks, item.id)
                }
                snapshot.connections.forEach { item ->
                    database.deviceConnectQueries.insertWithTimes(
                        id = item.id,
                        connectionType = item.connectionType,
                        firstConnection = item.firstConnection,
                        lastConnection = item.lastConnection,
                        category = item.category,
                        value = item.roleId,
                    )
                }
                snapshot.receiveShares.forEach { item ->
                    database.deviceReceiveShareQueries.insert(
                        id = item.id,
                        connectionType = item.connectionType,
                        path = item.path,
                    )
                }
            }
        }
        optionalKoin<DeviceSettingsState>()?.refresh()
    }
}

class RoleConfigurationSnapshotProvider(
    private val database: FolderSpanDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationSnapshotProvider, KoinComponent {
    override val key: String = ProConfigurationSnapshotKeys.ROLES

    override suspend fun read(): JsonElement = withContext(Dispatchers.Default) {
        json.encodeToJsonElement(
            RoleConfigurationSnapshot(
                roles = database.deviceRoleQueries.selectAll().executeAsListAwait().map { item ->
                    RoleSnapshotItem(
                        id = item.id,
                        name = item.name,
                        comment = item.comment,
                        sortOrder = item.sortOrder,
                    )
                },
                permissions = database.devicePermissionQueries.select().executeAsListAwait().map { item ->
                    DevicePermissionSnapshotItem(
                        id = item.id,
                        path = item.path,
                        useAll = item.useAll,
                        read = item.read,
                        write = item.write,
                        remove = item.remove,
                        rename = item.rename,
                        sortOrder = item.sortOrder,
                        comment = item.comment,
                    )
                },
                rolePermissions = database.deviceRoleDevicePermissionQueries.queryAll().executeAsListAwait().map { item ->
                    RolePermissionLinkSnapshotItem(
                        deviceRoleId = item.deviceRoleId,
                        devicePermissionId = item.devicePermissionId,
                    )
                },
            ),
        )
    }

    override suspend fun apply(value: JsonElement) {
        val snapshot = runCatching { json.decodeFromJsonElement<RoleConfigurationSnapshot>(value) }.getOrNull() ?: return
        if (snapshot.roles.isEmpty() && snapshot.permissions.isEmpty() && snapshot.rolePermissions.isEmpty()) return
        withContext(Dispatchers.Default) {
            database.transaction {
                database.deviceRoleDevicePermissionQueries.deleteAll()
                database.deviceRoleQueries.deleteAll()
                database.devicePermissionQueries.deleteAll()
                snapshot.roles.forEach { item ->
                    database.deviceRoleQueries.insertWithId(
                        id = item.id,
                        name = item.name,
                        comment = item.comment,
                        sortOrder = item.sortOrder,
                    )
                }
                snapshot.permissions.forEach { item ->
                    database.devicePermissionQueries.insertWithId(
                        id = item.id,
                        path = item.path,
                        useAll = item.useAll,
                        read = item.read,
                        write = item.write,
                        remove = item.remove,
                        rename = item.rename,
                        sortOrder = item.sortOrder,
                        comment = item.comment,
                    )
                }
                snapshot.rolePermissions.forEach { item ->
                    database.deviceRoleDevicePermissionQueries.insert(
                        deviceRoleId = item.deviceRoleId,
                        devicePermissionId = item.devicePermissionId,
                    )
                }
            }
        }
        optionalKoin<DeviceRoleState>()?.refresh()
        optionalKoin<DevicePermissionState>()?.refresh()
    }
}

class NetworkDrivesConfigurationSnapshotProvider(
    private val database: FolderSpanDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val codec: NetworkConfigurationSnapshotCodec = NetworkConfigurationSnapshotCodec(),
) : ConfigurationSnapshotProvider, KoinComponent {
    override val key: String = ProConfigurationSnapshotKeys.NETWORKS

    override suspend fun read(): JsonElement = withContext(Dispatchers.Default) {
        val networks = database.networkDriveQueries.selectAll().executeAsListAwait().mapNotNull { item ->
            runCatching {
                EncryptedNetworkSnapshotItem(
                    name = item.name,
                    protocol = item.protocol,
                    host = item.host,
                    username = item.username,
                    encryptedPassword = item.password,
                    pathSeparator = item.pathSeparator,
                    pinned = item.pinned != 0L,
                    encryptedExtras = item.extras,
                )
            }.getOrNull()
        }
        json.encodeToJsonElement(codec.encode(networks))
    }

    override suspend fun apply(value: JsonElement) {
        val snapshot = runCatching { json.decodeFromJsonElement<NetworkConfigurationSnapshot>(value) }.getOrNull() ?: return
        val networks = codec.decode(snapshot)
        withContext(Dispatchers.Default) {
            database.transaction {
                database.networkDriveQueries.deleteAll()
                networks.forEach { item ->
                    database.networkDriveQueries.insert(
                        name = item.name,
                        protocol = item.protocol,
                        host = item.host,
                        username = item.username,
                        password = item.encryptedPassword,
                        pathSeparator = item.pathSeparator,
                        pinned = if (item.pinned) 1L else 0L,
                        extras = item.encryptedExtras,
                    )
                }
            }
        }
        optionalKoin<NetworkState>()?.reloadPersisted()
    }
}

class WebRtcRoomsConfigurationSnapshotProvider(
    private val database: FolderSpanDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationSnapshotProvider, KoinComponent {
    override val key: String = ProConfigurationSnapshotKeys.WEB_RTC_ROOMS

    override suspend fun read(): JsonElement = withContext(Dispatchers.Default) {
        json.encodeToJsonElement(
            WebRtcRoomConfigurationSnapshot(
                rooms = database.webRtcRoomQueries.selectAll().executeAsListAwait().map { item ->
                    WebRtcRoomSnapshotItem(
                        name = item.name,
                        wssUrl = item.wssUrl,
                        roomId = item.roomId,
                        stunUrl = item.stunUrl,
                        turnUrl = item.turnUrl,
                        turnUsername = item.turnUsername,
                        encryptedTurnPassword = item.turnPassword,
                        source = com.folderspan.data.main.webrtc.WebRtcRoomSource.valueOf(item.source),
                        pinned = item.pinned != 0L,
                        sortOrder = item.sortOrder,
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                    )
                },
            ),
        )
    }

    override suspend fun apply(value: JsonElement) {
        val snapshot = runCatching { json.decodeFromJsonElement<WebRtcRoomConfigurationSnapshot>(value) }.getOrNull() ?: return
        withContext(Dispatchers.Default) {
            database.transaction {
                database.webRtcRoomQueries.selectAll().executeAsList().forEach { item ->
                    database.webRtcRoomQueries.deleteById(item.id)
                }
                snapshot.rooms.forEachIndexed { index, item ->
                    database.webRtcRoomQueries.insert(
                        name = item.name,
                        wssUrl = item.wssUrl,
                        roomId = item.roomId,
                        stunUrl = item.stunUrl,
                        turnUrl = item.turnUrl,
                        turnUsername = item.turnUsername,
                        turnPassword = item.encryptedTurnPassword,
                        source = item.source.name,
                        pinned = if (item.pinned) 1L else 0L,
                        sortOrder = item.sortOrder.takeIf { it > 0L } ?: index.toLong(),
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                    )
                }
            }
        }
        optionalKoin<WebRtcRoomState>()?.refreshRooms()
    }
}

class SyncTasksConfigurationSnapshotProvider(
    private val store: SyncTaskStore,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationSnapshotProvider, KoinComponent {
    override val key: String = ProConfigurationSnapshotKeys.SYNC_TASKS

    override suspend fun read(): JsonElement = withContext(Dispatchers.Default) {
        json.encodeToJsonElement(
            SyncTasksConfigurationSnapshot(
                tasks = store.loadTasks().map { task ->
                    task.copy(
                        lastRunAt = 0,
                        nextRunAt = 0,
                        lastStatus = com.folderspan.ui.state.main.SyncRunStatus.Idle,
                        lastMessage = "",
                    )
                },
            ),
        )
    }

    override suspend fun apply(value: JsonElement) {
        val snapshot = runCatching { json.decodeFromJsonElement<SyncTasksConfigurationSnapshot>(value) }.getOrNull() ?: return
        withContext(Dispatchers.Default) {
            store.loadTasks().forEach { task ->
                store.deleteTask(task.id)
            }
            snapshot.tasks.forEach { task ->
                store.saveTask(
                    task.copy(
                        lastRunAt = 0,
                        nextRunAt = 0,
                        lastStatus = com.folderspan.ui.state.main.SyncRunStatus.Idle,
                        lastMessage = "",
                    ),
                )
            }
        }
        optionalKoin<SyncState>()?.reloadTasksFromStore()
    }
}

class DataEncryptionKeySnapshotProvider : ConfigurationSnapshotProvider {
    override val key: String = ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY

    override suspend fun read(): JsonElement {
        val encoded = DataEncryptionKey.encodedForSync() ?: return JsonNull
        return JsonPrimitive(encoded)
    }

    override suspend fun apply(value: JsonElement) {
        val encoded = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (encoded.isEmpty()) return
        DataEncryptionKey.replaceFromRemote(encoded)
    }
}

private inline fun <reified T : Any> KoinComponent.optionalKoin(): T? =
    runCatching { get<T>() }.getOrNull()
