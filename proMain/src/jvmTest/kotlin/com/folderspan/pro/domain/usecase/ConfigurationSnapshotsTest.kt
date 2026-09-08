package com.folderspan.pro.domain.usecase

import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.domain.model.*
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationSnapshotsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @AfterTest
    fun tearDown() {
        DataEncryptionKey.init(MapSettings())
    }

    @Test
    fun snapshotModelsRoundTripAndIgnoreUnknownFields() {
        val deviceSnapshot = DeviceConfigurationSnapshot(
            devices = listOf(
                DeviceSnapshotItem(
                    id = "device-1",
                    name = "Phone",
                    host = "10.0.0.2",
                    port = 12040,
                    type = DeviceType.Android,
                    hasRemarks = true,
                ),
            ),
            connections = listOf(
                DeviceConnectionSnapshotItem(
                    id = "device-1",
                    connectionType = DeviceConnectType.AUTO_CONNECT,
                    firstConnection = 1,
                    lastConnection = 2,
                    category = DeviceCategory.SERVER,
                    roleId = 2,
                ),
            ),
        )
        val roleSnapshot = RoleConfigurationSnapshot(
            roles = listOf(RoleSnapshotItem(id = 7, name = "Ops", comment = "remote", sortOrder = 3)),
            rolePermissions = listOf(RolePermissionLinkSnapshotItem(deviceRoleId = 7, devicePermissionId = 4)),
        )
        val networkSnapshot = NetworkConfigurationSnapshot(
            networks = listOf(
                NetworkSnapshotItem(
                    name = "S3",
                    protocol = "S3",
                    host = "https://s3.example.test",
                    username = "AKIA",
                    encryptedPassword = "encrypted",
                    pathSeparator = "/",
                    pinned = true,
                    encryptedExtras = "extras",
                ),
            ),
        )
        val webRtcSnapshot = WebRtcRoomConfigurationSnapshot(
            rooms = listOf(
                WebRtcRoomSnapshotItem(
                    name = "Office",
                    wssUrl = "wss://signal.example/ws",
                    roomId = "office-room",
                    stunUrl = "stun:signal.example:3478",
                    turnUrl = "turn:signal.example:3478",
                    turnUsername = "alice",
                    encryptedTurnPassword = "encrypted-turn-password",
                    source = WebRtcRoomSource.Official,
                    pinned = true,
                    sortOrder = 3L,
                ),
            ),
        )
        val syncSnapshot = SyncTasksConfigurationSnapshot(
            tasks = listOf(
                SyncTask(
                    id = 8,
                    name = "Docs",
                    enabled = true,
                    sourceType = SyncEndpointType.Local,
                    sourcePath = "/docs",
                    targetType = SyncEndpointType.Network,
                    targetRef = "S3:https://s3.example.test:AKIA",
                    targetPath = "/backup",
                    conflictPolicy = SyncConflictPolicy.Replace,
                    scheduleType = SyncScheduleType.Manual,
                    lastStatus = SyncRunStatus.Idle,
                ),
            ),
        )

        assertEquals(deviceSnapshot, json.decodeFromString<DeviceConfigurationSnapshot>(json.encodeToString(deviceSnapshot)))
        assertEquals(roleSnapshot, json.decodeFromString<RoleConfigurationSnapshot>(json.encodeToString(roleSnapshot)))
        assertEquals(networkSnapshot, json.decodeFromString<NetworkConfigurationSnapshot>(json.encodeToString(networkSnapshot)))
        assertEquals(webRtcSnapshot, json.decodeFromString<WebRtcRoomConfigurationSnapshot>(json.encodeToString(webRtcSnapshot)))
        assertEquals(syncSnapshot, json.decodeFromString<SyncTasksConfigurationSnapshot>(json.encodeToString(syncSnapshot)))

        val decodedUnknown = json.decodeFromString<NetworkConfigurationSnapshot>(
            """{"version":1,"unknown":"ignored","networks":[{"name":"FTP","protocol":"FTP","host":"host","username":"user","pathSeparator":"/","unknown":1}]}""",
        )
        assertEquals("FTP", decodedUnknown.networks.single().name)
    }

    @Test
    fun networkSnapshotPreservesLocalEncryptedCredentialsAndExcludesPlaintext() = runTest {
        val codec = NetworkConfigurationSnapshotCodec()
        val encryptedPassword = "encrypted-password-from-local-store"
        val encryptedExtras = "encrypted-extras-from-local-store".encodeToByteArray()
        val encodedExtras = Base64.encode(encryptedExtras)
        val snapshot = codec.encode(
            networks = listOf(
                EncryptedNetworkSnapshotItem(
                    name = "Secure",
                    protocol = "SFTP",
                    host = "10.0.0.2:22",
                    username = "alice",
                    encryptedPassword = encryptedPassword,
                    pathSeparator = "/",
                    pinned = false,
                    encryptedExtras = encryptedExtras,
                ),
            ),
        )

        val remoteJson = json.encodeToString(NetworkConfigurationSnapshot.serializer(), snapshot)

        assertFalse(remoteJson.contains("plain-password"))
        assertFalse(remoteJson.contains("PRIVATE_KEY"))
        assertFalse(remoteJson.contains("KNOWN_HOSTS"))
        assertFalse(remoteJson.contains("SESSION_TOKEN"))
        assertTrue(remoteJson.contains(encryptedPassword))
        assertTrue(remoteJson.contains(encodedExtras))

        val restored = codec.decode(snapshot)
        assertEquals(encryptedPassword, restored.single().encryptedPassword)
        assertEquals(encryptedExtras.toList(), restored.single().encryptedExtras.toList())
    }

    @Test
    fun networkSnapshotKeepsOpaqueEncryptedCredentialValues() = runTest {
        val codec = NetworkConfigurationSnapshotCodec()
        val encryptedExtras = "not-valid-extras-ciphertext".encodeToByteArray()
        val valid = codec.encode(
            listOf(
                EncryptedNetworkSnapshotItem(
                    name = "Valid",
                    protocol = "FTP",
                    host = "10.0.0.3:21",
                    username = "user",
                    encryptedPassword = "not-valid-ciphertext",
                    pathSeparator = "/",
                    pinned = false,
                    encryptedExtras = encryptedExtras,
                ),
            ),
        ).networks.single()
        val snapshot = NetworkConfigurationSnapshot(
            networks = listOf(
                valid,
                valid.copy(name = "StillValid"),
            ),
        )

        val restored = codec.decode(snapshot)

        assertEquals(listOf("Valid", "StillValid"), restored.map { item -> item.name })
        assertEquals("not-valid-ciphertext", restored.first().encryptedPassword)
        assertEquals(encryptedExtras.toList(), restored.first().encryptedExtras.toList())
    }

    @Test
    fun configurationSnapshotProviderUploadsAndAppliesAllSnapshotKeys() = runTest {
        val settings = MapSettings()
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.DEVICES,
                    value = JsonObject(emptyMap()),
                    updatedAt = 100,
                ),
                DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.ROLES,
                    value = JsonObject(emptyMap()),
                    updatedAt = 120,
                ),
            ),
        )
        val provider = RecordingConfigurationSnapshotProvider(
            key = ProConfigurationSnapshotKeys.DEVICES,
            payload = json.encodeToJsonElement(DeviceConfigurationSnapshot()),
        )
        val roleProvider = RecordingConfigurationSnapshotProvider(
            key = ProConfigurationSnapshotKeys.ROLES,
            payload = json.encodeToJsonElement(RoleConfigurationSnapshot()),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { com.folderspan.pro.core.network.DeviceIdentity("JVM", "device-1", "Desktop") },
            configurationSnapshots = listOf(provider, roleProvider),
            timestampProvider = { 99 },
        )

        service.uploadConfigurationSnapshots(settings, "token-value")
        service.sync(settings, "token-value")

        assertEquals(
            listOf(ProConfigurationSnapshotKeys.DEVICES, ProConfigurationSnapshotKeys.ROLES),
            repository.savedEntries.map { item -> item.key },
        )
        assertTrue(provider.applied)
        assertTrue(roleProvider.applied)
        assertEquals(120L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun configurationSnapshotUploadsSaveOnlyRequestedKeyWithSingleSettingCall() = runTest {
        val settings = MapSettings()
        val repository = RecordingSettingRepository(remoteEntries = emptyList())
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { com.folderspan.pro.core.network.DeviceIdentity("JVM", "device-1", "Desktop") },
            configurationSnapshots = listOf(
                RecordingConfigurationSnapshotProvider(
                    key = ProConfigurationSnapshotKeys.DEVICES,
                    payload = json.encodeToJsonElement(DeviceConfigurationSnapshot()),
                ),
                RecordingConfigurationSnapshotProvider(
                    key = ProConfigurationSnapshotKeys.ROLES,
                    payload = json.encodeToJsonElement(RoleConfigurationSnapshot()),
                ),
                RecordingConfigurationSnapshotProvider(
                    key = ProConfigurationSnapshotKeys.NETWORKS,
                    payload = json.encodeToJsonElement(NetworkConfigurationSnapshot()),
                ),
                RecordingConfigurationSnapshotProvider(
                    key = ProConfigurationSnapshotKeys.WEB_RTC_ROOMS,
                    payload = json.encodeToJsonElement(WebRtcRoomConfigurationSnapshot()),
                ),
                RecordingConfigurationSnapshotProvider(
                    key = ProConfigurationSnapshotKeys.SYNC_TASKS,
                    payload = json.encodeToJsonElement(SyncTasksConfigurationSnapshot()),
                ),
            ),
            timestampProvider = { 99 },
        )

        service.uploadDeviceConfigurationSnapshot(settings, "token-value")
        service.uploadRoleConfigurationSnapshot(settings, "token-value")
        service.uploadNetworkConfigurationSnapshot(settings, "token-value")
        service.uploadWebRtcRoomsConfigurationSnapshot(settings, "token-value")
        service.uploadSyncTasksConfigurationSnapshot(settings, "token-value")

        assertEquals(
            listOf(
                ProConfigurationSnapshotKeys.DEVICES,
                ProConfigurationSnapshotKeys.ROLES,
                ProConfigurationSnapshotKeys.NETWORKS,
                ProConfigurationSnapshotKeys.WEB_RTC_ROOMS,
                ProConfigurationSnapshotKeys.SYNC_TASKS,
            ),
            repository.savedEntries.map { item -> item.key },
        )
        assertEquals(5, repository.saveSettingCalls)
        assertEquals(0, repository.batchSaveSettingCalls)
    }

    private class RecordingSettingRepository(
        private val remoteEntries: List<DeviceSettingEntry>,
    ) : SettingRepository {
        val savedEntries = mutableListOf<DeviceSettingEntry>()
        var saveSettingCalls = 0
        var batchSaveSettingCalls = 0

        override suspend fun listSettings(
            type: String,
            targetId: String,
            timestamp: Long,
            token: String,
        ): ApiResult<List<DeviceSettingEntry>> =
            ApiResult.Success(remoteEntries)

        override suspend fun saveSetting(
            type: String,
            targetId: String,
            entry: DeviceSettingEntry,
            token: String,
        ): com.folderspan.pro.core.common.JsonResult {
            saveSettingCalls += 1
            savedEntries += entry
            return ApiResult.Success(kotlinx.serialization.json.JsonNull)
        }

        override suspend fun batchSaveSettings(
            items: List<RemoteSettingSave>,
            token: String,
        ): com.folderspan.pro.core.common.JsonResult {
            batchSaveSettingCalls += 1
            savedEntries += items.map { item ->
                DeviceSettingEntry(
                    key = item.key,
                    value = item.value,
                )
            }
            return ApiResult.Success(kotlinx.serialization.json.JsonNull)
        }
    }

    private class RecordingConfigurationSnapshotProvider(
        override val key: String,
        private val payload: kotlinx.serialization.json.JsonElement,
    ) : ConfigurationSnapshotProvider {
        var applied = false

        override suspend fun read(): kotlinx.serialization.json.JsonElement = payload

        override suspend fun apply(value: kotlinx.serialization.json.JsonElement) {
            applied = true
        }
    }
}
