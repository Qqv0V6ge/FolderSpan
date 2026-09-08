package com.folderspan.pro.domain.usecase

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.DeviceIdentity
import com.folderspan.pro.domain.model.*
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.readFileShareAccessKeyConfig
import com.folderspan.service.http.writeFileShareAccessKeyConfig
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class DeviceSettingsSyncServiceTest {
    @AfterTest
    fun tearDown() {
        DataEncryptionKey.init(MapSettings())
    }

    @Test
    fun uploadSettingSavesOnlyChangedSyncableSettingForCurrentDeviceTarget() = runTest {
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putString(SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS, """["/tmp/share"]""")
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            timestampProvider = { 1234L },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_FILE_SHARE_PORT, "token-value")

        assertEquals("device-1", repository.savedTargetId)
        assertEquals("token-value", repository.savedToken)
        assertEquals(SettingsUtils.KEY_FILE_SHARE_PORT, repository.savedEntry?.key)
        assertEquals(JsonPrimitive(13000), repository.savedEntry?.value)
        assertEquals(1234L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
        assertEquals(
            mapOf(SettingsUtils.KEY_FILE_SHARE_PORT to 1234L),
            settings.readEntryTimestamps(),
        )
    }

    @Test
    fun uploadSettingUsesServerUpdatedAtWhenSaveResponseIncludesUpdatedAt() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Success(
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(0),
                        "data" to JsonObject(mapOf("updatedAt" to JsonPrimitive(5678L))),
                    ),
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            timestampProvider = { 1234L },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_FILE_SHARE_PORT, "token-value")

        assertEquals(5678L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun uploadSettingKeepsMaximumLocalTimestampWhenSaveResponseIsOlder() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 200L)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS,
            Json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                mapOf(SettingsUtils.KEY_FILE_SHARE_PORT to 180L),
            ),
        )
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Success(
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(0),
                        "data" to JsonObject(mapOf("updatedAt" to JsonPrimitive(150L))),
                    ),
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_FILE_SHARE_PORT, "token-value")

        assertEquals(200L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
        assertEquals(180L, settings.readEntryTimestamps()[SettingsUtils.KEY_FILE_SHARE_PORT])
    }

    @Test
    fun uploadSettingIgnoresNonUpdatedAtTimestampFieldsFromSaveResponse() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Success(
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(0),
                        "data" to JsonObject(
                            mapOf(
                                "updated_at" to JsonPrimitive(5678L),
                                "timestamp" to JsonPrimitive(6789L),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            timestampProvider = { 1234L },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_FILE_SHARE_PORT, "token-value")

        assertEquals(1234L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun uploadSettingIgnoresNonSyncableAndMissingKeys() = runTest {
        val settings = MapSettings()
        settings.putString(SettingsUtils.KEY_CRYPTO_KEY, "secret")
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_CRYPTO_KEY, "token-value")
        service.uploadSetting(settings, SettingsUtils.KEY_APPEARANCE_THEME_MODE, "token-value")

        assertNull(repository.savedTargetId)
        assertNull(repository.savedEntry)
    }

    @Test
    fun pullMissingRemoteSettingsOverwritesLocalValuesWithRemoteEntries() = runTest {
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_ENABLED, JsonPrimitive(true)),
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(14000), updatedAt = 8L),
                DeviceSettingEntry(SettingsUtils.KEY_CRYPTO_KEY, JsonPrimitive("secret"), updatedAt = 20L),
                DeviceSettingEntry(
                    SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS,
                    JsonArray(listOf(JsonPrimitive("/remote"))),
                    updatedAt = 12L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.pullMissingRemoteSettings(settings, "token-value")

        assertEquals(true, settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false))
        assertEquals(14000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals("""["/remote"]""", settings.getString(SettingsUtils.KEY_EASY_FILE_SHARE_SHARE_PATHS, ""))
        assertFalse(settings.hasKey(SettingsUtils.KEY_CRYPTO_KEY))
        assertEquals(1L, repository.listTimestamp)
        assertEquals(20L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun pullRemoteSettingsOverwritesFileShareAccessKeyConfigAtomically() = runTest {
        val localConfig = FileShareAccessKeyConfig(enabled = true, value = "old-key")
        val remoteConfig = FileShareAccessKeyConfig(enabled = true, value = "new-key")
        val remoteSettings = MapSettings().apply {
            writeFileShareAccessKeyConfig(remoteConfig)
        }
        val settings = MapSettings().apply {
            writeFileShareAccessKeyConfig(localConfig)
        }
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY,
                    value = JsonPrimitive(
                        remoteSettings.getString(SettingsUtils.KEY_FILE_SHARE_ACCESS_KEY, "")
                    ),
                    updatedAt = 8L,
                )
            )
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.pullMissingRemoteSettings(settings, "token-value")

        assertEquals(remoteConfig, settings.readFileShareAccessKeyConfig())
    }

    @Test
    fun pullMissingRemoteSettingsNotifiesWhenSyncableSettingsAreApplied() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        var refreshCount = 0
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(14000), updatedAt = 8L),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            onSettingsApplied = { refreshCount += 1 },
        )

        service.pullMissingRemoteSettings(settings, "token-value")

        assertEquals(14000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals(1, refreshCount)
    }

    @Test
    fun pullMissingRemoteSettingsUsesStoredTimestamp() = runTest {
        val settings = MapSettings()
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 34L)
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.pullMissingRemoteSettings(settings, "token-value")

        assertEquals(34L, repository.listTimestamp)
    }

    @Test
    fun syncUsesActiveRequestHeaderDeviceKeyAsTargetIdWhenPresent() = runTest {
        val settings = MapSettings()
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "current-device", name = "Workstation") },
            requestHeaderDeviceKeyProvider = { "override-device" },
        )

        service.sync(settings, "token-value")

        assertEquals("override-device", repository.listTargetId)
    }

    @Test
    fun pullAllRemoteSettingsForTargetOverwritesLocalValuesAndUpdatesTimestampAfterWrites() = runTest {
        val settings = RecordingSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 99L)
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_ENABLED, JsonPrimitive(true), updatedAt = 10L),
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(16000), updatedAt = 20L),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "current-device", name = "Workstation") },
        )
        settings.writeOrder.clear()

        service.pullAllRemoteSettingsForTarget(
            settings = settings,
            targetId = "source-device",
            token = "token-value",
        )

        assertEquals("source-device", repository.listTargetId)
        assertEquals(1L, repository.listTimestamp)
        assertEquals(true, settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false))
        assertEquals(16000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals(20L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
        assertEquals(
            listOf(
                "putBoolean:${SettingsUtils.KEY_FILE_SHARE_ENABLED}",
                "putInt:${SettingsUtils.KEY_FILE_SHARE_PORT}",
                "putLong:${SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP}",
            ),
            settings.writeOrder,
        )
    }

    @Test
    fun pullAppliesRemoteDataEncryptionKeyBeforeOtherEntries() = runTest {
        val settings = MapSettings()
        DataEncryptionKey.init(settings)
        val remoteDek = Base64.encode(ByteArray(32) { 9 })
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_FILE_SHARE_PORT,
                    value = JsonPrimitive(17000),
                    updatedAt = 40L,
                ),
                DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY,
                    value = JsonPrimitive(remoteDek),
                    updatedAt = 30L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            configurationSnapshots = listOf(DataEncryptionKeySnapshotProvider()),
        )

        service.pullAllRemoteSettingsForTarget(
            settings = settings,
            targetId = "source-device",
            token = "token-value",
        )

        assertEquals(remoteDek, DataEncryptionKey.encodedForSync())
        assertEquals(17000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
    }

    @Test
    fun syncUploadsLocalDataEncryptionKeyAfterPull() = runTest {
        val settings = MapSettings()
        DataEncryptionKey.init(settings)
        val localDek = Base64.encode(ByteArray(32) { 21 })
        settings.putString(SettingsUtils.KEY_CRYPTO_KEY, localDek)
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.sync(settings, "token-value")

        assertEquals(listOf("list", "save"), repository.calls)
        assertEquals(ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY, repository.savedEntry?.key)
        assertEquals(JsonPrimitive(localDek), repository.savedEntry?.value)
    }

    @Test
    fun syncPullsRemoteSettingsForCurrentTarget() = runTest {
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(15000))),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.sync(settings, "token-value")

        assertEquals(listOf("list"), repository.calls)
        assertEquals(15000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
    }

    @Test
    fun syncMergesRemoteBookmarkAndFavoriteSnapshots() = runTest {
        val settings = MapSettings()
        val snapshots = RecordingSyncSnapshots(
            bookmarks = listOf(
                SyncBookmarkSnapshotItem(
                    name = AppStrings.ui_test_device_settings_sync_service_local_download,
                    type = DrawerBookmarkType.Download,
                    path = "/local/download",
                    icon = null,
                    sort = 1L,
                ),
            ),
            favorites = listOf(
                SyncFavoriteSnapshotItem(
                    name = AppStrings.ui_test_device_settings_sync_service_local_document,
                    isDirectory = false,
                    isFixed = false,
                    path = "/local/doc.txt",
                    mineType = "text/plain",
                    size = 12L,
                    createdDate = 1L,
                    updatedDate = 2L,
                    protocol = FileProtocol.Local,
                    protocolId = null,
                ),
            ),
        )
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT,
                    value = Json.encodeToJsonElement(
                        listOf(
                            SyncBookmarkSnapshotItem(
                                name = AppStrings.ui_test_device_settings_sync_service_remote_download,
                                type = DrawerBookmarkType.Download,
                                path = "/local/download",
                                icon = null,
                                sort = 1L,
                            ),
                            SyncBookmarkSnapshotItem(
                                name = AppStrings.ui_test_device_settings_sync_service_remote_image,
                                type = DrawerBookmarkType.Image,
                                path = "/remote/pictures",
                                icon = null,
                                sort = 2L,
                            ),
                        ),
                    ),
                    updatedAt = 40L,
                ),
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT,
                    value = Json.encodeToJsonElement(
                        listOf(
                            SyncFavoriteSnapshotItem(
                                name = AppStrings.ui_test_device_settings_sync_service_local_document_remote_copy,
                                isDirectory = false,
                                isFixed = true,
                                path = "/local/doc.txt",
                                mineType = "text/plain",
                                size = 12L,
                                createdDate = 1L,
                                updatedDate = 5L,
                                protocol = FileProtocol.Local,
                                protocolId = null,
                            ),
                            SyncFavoriteSnapshotItem(
                                name = AppStrings.ui_test_device_settings_sync_service_remote_directory,
                                isDirectory = true,
                                isFixed = false,
                                path = "/remote/folder",
                                mineType = "",
                                size = 0L,
                                createdDate = 3L,
                                updatedDate = 4L,
                                protocol = FileProtocol.Network,
                                protocolId = "network-1",
                            ),
                        ),
                    ),
                    updatedAt = 45L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            syncSnapshots = snapshots,
        )

        service.sync(settings, "token-value")

        assertEquals(
            listOf("/local/download", "/remote/pictures"),
            snapshots.appliedBookmarks.map { item -> item.path },
        )
        assertEquals(AppStrings.ui_test_device_settings_sync_service_local_download, snapshots.appliedBookmarks.first().name)
        assertEquals(
            listOf("/local/doc.txt", "/remote/folder"),
            snapshots.appliedFavorites.map { item -> item.path },
        )
        assertEquals(false, snapshots.appliedFavorites.first().isFixed)
        assertEquals(45L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun uploadBookmarkAndFavoriteSnapshotsSaveCurrentLists() = runTest {
        val settings = MapSettings()
        val snapshots = RecordingSyncSnapshots(
            bookmarks = listOf(
                SyncBookmarkSnapshotItem(
                    name = AppStrings.ui_pictures,
                    type = DrawerBookmarkType.Image,
                    path = "/pictures",
                    icon = null,
                    sort = 1L,
                ),
            ),
            favorites = listOf(
                SyncFavoriteSnapshotItem(
                    name = AppStrings.ui_test_device_settings_sync_service_log,
                    isDirectory = false,
                    isFixed = true,
                    path = "/tmp/log.txt",
                    mineType = "text/plain",
                    size = 24L,
                    createdDate = 10L,
                    updatedDate = 11L,
                    protocol = FileProtocol.Local,
                    protocolId = null,
                ),
            ),
        )
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            syncSnapshots = snapshots,
            timestampProvider = { 4321L },
        )

        service.uploadBookmarkSnapshot(settings, "token-value")
        service.uploadFavoriteSnapshot(settings, "token-value")

        assertEquals(
            listOf(
                SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT,
                SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT,
            ),
            repository.savedEntries.map { item -> item.key },
        )
        assertEquals("device-1", repository.savedTargetId)
        assertEquals("token-value", repository.savedToken)
        assertEquals(4321L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun failedSettingUploadRecordsOnlyChangedKeyAndFlushUploadsOnlyThatKey() = runTest {
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        val repository = RecordingSettingRepository(saveResult = ApiResult.Failure("offline"))
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.uploadSetting(settings, SettingsUtils.KEY_FILE_SHARE_PORT, "token-value")

        assertEquals(
            setOf(SettingsUtils.KEY_FILE_SHARE_PORT),
            service.pendingUploadKeys(settings).settingKeys,
        )

        repository.saveResult = ApiResult.Success(JsonObject(mapOf("updatedAt" to JsonPrimitive(88L))))
        repository.savedEntries.clear()
        service.flushPendingUploads(settings, "token-value")

        assertEquals(listOf(SettingsUtils.KEY_FILE_SHARE_PORT), repository.savedEntries.map { it.key })
        assertTrue(service.pendingUploadKeys(settings).settingKeys.isEmpty())
        assertEquals(88L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun failedSnapshotUploadRecordsOnlyThatSnapshotAndFlushUploadsOnlyDirtySnapshot() = runTest {
        val settings = MapSettings()
        val snapshots = RecordingSyncSnapshots(
            bookmarks = listOf(
                SyncBookmarkSnapshotItem(
                    name = AppStrings.ui_pictures,
                    type = DrawerBookmarkType.Image,
                    path = "/pictures",
                ),
            ),
            favorites = listOf(
                SyncFavoriteSnapshotItem(
                    name = AppStrings.ui_test_device_settings_sync_service_log,
                    isDirectory = false,
                    isFixed = true,
                    path = "/tmp/log.txt",
                    mineType = "text/plain",
                    size = 24L,
                    createdDate = 10L,
                    updatedDate = 11L,
                    protocol = FileProtocol.Local,
                    protocolId = null,
                ),
            ),
        )
        val repository = RecordingSettingRepository(saveResult = ApiResult.Failure("offline"))
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            syncSnapshots = snapshots,
        )

        service.uploadBookmarkSnapshot(settings, "token-value")

        assertEquals(
            setOf(SyncSnapshotKey.Bookmarks),
            service.pendingUploadKeys(settings).snapshotKeys,
        )

        repository.saveResult = ApiResult.Success(JsonNull)
        repository.savedEntries.clear()
        service.flushPendingUploads(settings, "token-value")

        assertEquals(listOf(SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT), repository.savedEntries.map { it.key })
        assertTrue(service.pendingUploadKeys(settings).snapshotKeys.isEmpty())
    }

    @Test
    fun manualSyncUploadsOnlySelectedPendingCategoriesWithoutPullingRemoteIncrement() = runTest {
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            Json.encodeToString(
                listOf(
                    SettingsUtils.KEY_FILE_SHARE_ENABLED,
                    SettingsUtils.KEY_FILE_SHARE_PORT,
                ),
            ),
        )
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS,
            Json.encodeToString(
                listOf(
                    SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT,
                    ProConfigurationSnapshotKeys.ROLES,
                    ProConfigurationSnapshotKeys.NETWORKS,
                    ProConfigurationSnapshotKeys.WEB_RTC_ROOMS,
                ),
            ),
        )
        val snapshots = RecordingSyncSnapshots(
            bookmarks = listOf(
                SyncBookmarkSnapshotItem(
                    name = AppStrings.ui_pictures,
                    type = DrawerBookmarkType.Image,
                    path = "/pictures",
                ),
            ),
        )
        val configuration = RecordingConfigurationSnapshotProvider(
            key = ProConfigurationSnapshotKeys.ROLES,
            value = JsonObject(mapOf("version" to JsonPrimitive(1))),
        )
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(18000), updatedAt = 90L),
                DeviceSettingEntry(SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT, Json.encodeToJsonElement(emptyList<SyncBookmarkSnapshotItem>()), updatedAt = 91L),
                DeviceSettingEntry(ProConfigurationSnapshotKeys.ROLES, JsonObject(mapOf("version" to JsonPrimitive(2))), updatedAt = 92L),
                DeviceSettingEntry(ProConfigurationSnapshotKeys.NETWORKS, JsonObject(mapOf("version" to JsonPrimitive(3))), updatedAt = 93L),
                DeviceSettingEntry(ProConfigurationSnapshotKeys.WEB_RTC_ROOMS, JsonObject(mapOf("version" to JsonPrimitive(4))), updatedAt = 94L),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            syncSnapshots = snapshots,
            configurationSnapshots = listOf(configuration),
        )

        val result = service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.Bookmarks, ManualSyncCategory.Roles),
        )

        assertEquals(listOf("save", "save"), repository.calls)
        assertEquals(
            listOf(SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT, ProConfigurationSnapshotKeys.ROLES),
            repository.savedEntries.map { it.key },
        )
        assertEquals(13000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals(null, configuration.appliedValue)
        assertEquals(
            setOf(ManualSyncCategory.Bookmarks, ManualSyncCategory.Roles),
            result.succeeded,
        )
        assertTrue(result.failed.isEmpty())
        assertEquals(
            setOf(SettingsUtils.KEY_FILE_SHARE_ENABLED, SettingsUtils.KEY_FILE_SHARE_PORT),
            service.pendingUploadKeys(settings).settingKeys,
        )
        assertEquals(
            setOf(SyncSnapshotKey.Networks, SyncSnapshotKey.WebRtc),
            service.pendingUploadKeys(settings).snapshotKeys,
        )
    }

    @Test
    fun manualSyncDoesNotUploadSelectedCategoriesWithoutPendingChanges() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        val snapshots = RecordingSyncSnapshots(
            bookmarks = listOf(
                SyncBookmarkSnapshotItem(
                    name = AppStrings.ui_pictures,
                    type = DrawerBookmarkType.Image,
                    path = "/pictures",
                ),
            ),
        )
        val repository = RecordingSettingRepository()
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            syncSnapshots = snapshots,
        )

        val result = service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.Settings, ManualSyncCategory.Bookmarks),
        )

        assertEquals(listOf("list"), repository.calls)
        assertTrue(repository.savedEntries.isEmpty())
        assertTrue(result.succeeded.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun manualSyncSkipsPullAfterSuccessfulUploadAndStoresServerUpdatedAt() = runTest {
        val settings = MapSettings()
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 100L)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS,
            Json.encodeToString(listOf(ProConfigurationSnapshotKeys.WEB_RTC_ROOMS)),
        )
        val configuration = RecordingConfigurationSnapshotProvider(
            key = ProConfigurationSnapshotKeys.WEB_RTC_ROOMS,
            value = JsonObject(mapOf("rooms" to JsonArray(emptyList()))),
        )
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Success(
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(0),
                        "msg" to JsonPrimitive("OK"),
                        "data" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("device"),
                                "targetId" to JsonPrimitive("device-1"),
                                "key" to JsonPrimitive(ProConfigurationSnapshotKeys.WEB_RTC_ROOMS),
                                "value" to JsonObject(mapOf("rooms" to JsonArray(emptyList()))),
                                "updatedAt" to JsonPrimitive(1782748405865L),
                            ),
                        ),
                    ),
                ),
            ),
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.WEB_RTC_ROOMS,
                    value = JsonObject(mapOf("version" to JsonPrimitive(2))),
                    updatedAt = 1782748406000L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            configurationSnapshots = listOf(configuration),
        )

        val result = service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.WebRtc),
        )

        assertEquals(listOf("save"), repository.calls)
        assertEquals(1782748405865L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
        assertEquals(1782748405865L, settings.readEntryTimestamps()[ProConfigurationSnapshotKeys.WEB_RTC_ROOMS])
        assertEquals(setOf(ManualSyncCategory.WebRtc), result.succeeded)
        assertTrue(result.failed.isEmpty())
        assertTrue(service.pendingUploadKeys(settings).snapshotKeys.isEmpty())
        assertEquals(null, configuration.appliedValue)
    }

    @Test
    fun manualSyncUsesGlobalTimestampForSelectedCategories() = runTest {
        val settings = MapSettings()
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 150L)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS,
            Json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                mapOf(
                    SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT to 200L,
                    ProConfigurationSnapshotKeys.ROLES to 40L,
                    SettingsUtils.KEY_FILE_SHARE_PORT to 70L,
                ),
            ),
        )
        val configuration = RecordingConfigurationSnapshotProvider(
            key = ProConfigurationSnapshotKeys.ROLES,
            value = JsonObject(mapOf("version" to JsonPrimitive(1))),
        )
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT,
                    value = Json.encodeToJsonElement(emptyList<SyncBookmarkSnapshotItem>()),
                    updatedAt = 220L,
                ),
                DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.ROLES,
                    value = JsonObject(mapOf("version" to JsonPrimitive(2))),
                    updatedAt = 190L,
                ),
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_FILE_SHARE_PORT,
                    value = JsonPrimitive(18000),
                    updatedAt = 120L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            configurationSnapshots = listOf(configuration),
        )

        service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.Roles),
        )

        assertEquals(150L, repository.listTimestamp)
        assertEquals(JsonObject(mapOf("version" to JsonPrimitive(2))), configuration.appliedValue)
        val timestamps = settings.readEntryTimestamps()
        assertEquals(200L, timestamps[SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT])
        assertEquals(190L, timestamps[ProConfigurationSnapshotKeys.ROLES])
        assertEquals(70L, timestamps[SettingsUtils.KEY_FILE_SHARE_PORT])
        assertFalse(settings.hasKey(SettingsUtils.KEY_FILE_SHARE_PORT))
    }

    @Test
    fun manualSyncKeepsUnreturnedEntryTimestampsWhenGlobalTimestampAdvances() = runTest {
        val settings = MapSettings()
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 100L)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS,
            Json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                mapOf(
                    SettingsUtils.KEY_FILE_SHARE_ENABLED to 100L,
                    SettingsUtils.KEY_FILE_SHARE_PORT to 100L,
                ),
            ),
        )
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(
                    key = SettingsUtils.KEY_FILE_SHARE_PORT,
                    value = JsonPrimitive(18000),
                    updatedAt = 240L,
                ),
            ),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.Settings),
        )
        service.manualSync(
            settings = settings,
            token = "token-value",
            categories = setOf(ManualSyncCategory.Settings),
        )

        assertEquals(listOf(100L, 240L), repository.listTimestamps)
        val timestamps = settings.readEntryTimestamps()
        assertEquals(100L, timestamps[SettingsUtils.KEY_FILE_SHARE_ENABLED])
        assertEquals(240L, timestamps[SettingsUtils.KEY_FILE_SHARE_PORT])
    }

    @Test
    fun manualSyncWithNoSelectionDoesNothing() = runTest {
        val settings = MapSettings()
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            Json.encodeToString(listOf(SettingsUtils.KEY_FILE_SHARE_PORT)),
        )
        val repository = RecordingSettingRepository(
            remoteEntries = listOf(DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(18000), updatedAt = 90L)),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        val result = service.manualSync(
            settings = settings,
            token = "token-value",
            categories = emptySet(),
        )

        assertTrue(repository.calls.isEmpty())
        assertTrue(result.succeeded.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(
            setOf(SettingsUtils.KEY_FILE_SHARE_PORT),
            service.pendingUploadKeys(settings).settingKeys,
        )
    }

    @Test
    fun syncSkipsPullAfterSuccessfulPendingUploadAndStoresServerUpdatedAt() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 50L)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            Json.encodeToString(listOf(SettingsUtils.KEY_FILE_SHARE_PORT)),
        )
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Success(JsonObject(mapOf("updatedAt" to JsonPrimitive(120L)))),
            remoteEntries = listOf(DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_ENABLED, JsonPrimitive(false), updatedAt = 100L)),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.sync(settings, "token-value")

        assertEquals(listOf("save"), repository.calls)
        assertEquals(listOf(SettingsUtils.KEY_FILE_SHARE_PORT), repository.savedEntries.map { it.key })
        assertEquals(null, repository.listTimestamp)
        assertEquals(120L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
        assertEquals(true, settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, true))
        assertTrue(service.pendingUploadKeys(settings).settingKeys.isEmpty())
    }

    @Test
    fun syncDoesNotPullRemoteWhenPendingUploadStillFails() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putString(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            Json.encodeToString(listOf(SettingsUtils.KEY_FILE_SHARE_PORT)),
        )
        val repository = RecordingSettingRepository(
            saveResult = ApiResult.Failure("offline"),
            remoteEntries = listOf(DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(12040), updatedAt = 100L)),
        )
        val service = DeviceSettingsSyncService(
            repository = repository,
            deviceIdentityProvider = { DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
        )

        service.sync(settings, "token-value")

        assertEquals(listOf("save"), repository.calls)
        assertEquals(13000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals(
            setOf(SettingsUtils.KEY_FILE_SHARE_PORT),
            service.pendingUploadKeys(settings).settingKeys,
        )
    }

    private class RecordingSettingRepository(
        private val remoteEntries: List<DeviceSettingEntry> = emptyList(),
        var saveResult: JsonResult = ApiResult.Success(JsonNull),
    ) : SettingRepository {
        val calls = mutableListOf<String>()
        var savedTargetId: String? = null
        var savedToken: String? = null
        var savedEntry: DeviceSettingEntry? = null
        val savedEntries = mutableListOf<DeviceSettingEntry>()
        var listTargetId: String? = null
        var listTimestamp: Long? = null
        val listTimestamps = mutableListOf<Long>()

        override suspend fun listDeviceTargets(token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun cloneTargetSettings(command: CloneSettingTargetCommand, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun deleteTargetSettings(type: String, targetId: String, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun listDeviceSettings(targetId: String, timestamp: Long, token: String): ApiResult<List<DeviceSettingEntry>> {
            calls.add("list")
            listTargetId = targetId
            listTimestamp = timestamp
            listTimestamps += timestamp
            return ApiResult.Success(remoteEntries)
        }

        override suspend fun saveDeviceSetting(
            targetId: String,
            entry: DeviceSettingEntry,
            token: String,
        ): JsonResult {
            calls.add("save")
            savedTargetId = targetId
            savedToken = token
            savedEntry = entry
            savedEntries += entry
            return saveResult
        }
    }

    private class RecordingSettings(
        private val delegate: Settings = MapSettings(),
    ) : Settings by delegate {
        val writeOrder = mutableListOf<String>()

        override fun putBoolean(key: String, value: Boolean) {
            writeOrder += "putBoolean:$key"
            delegate.putBoolean(key, value)
        }

        override fun putInt(key: String, value: Int) {
            writeOrder += "putInt:$key"
            delegate.putInt(key, value)
        }

        override fun putLong(key: String, value: Long) {
            writeOrder += "putLong:$key"
            delegate.putLong(key, value)
        }
    }

    private class RecordingSyncSnapshots(
        private val bookmarks: List<SyncBookmarkSnapshotItem> = emptyList(),
        private val favorites: List<SyncFavoriteSnapshotItem> = emptyList(),
    ) : SyncSnapshots {
        var appliedBookmarks: List<SyncBookmarkSnapshotItem> = emptyList()
        var appliedFavorites: List<SyncFavoriteSnapshotItem> = emptyList()

        override suspend fun readBookmarks(): List<SyncBookmarkSnapshotItem> = bookmarks

        override suspend fun replaceBookmarks(items: List<SyncBookmarkSnapshotItem>) {
            appliedBookmarks = items
        }

        override suspend fun readFavorites(): List<SyncFavoriteSnapshotItem> = favorites

        override suspend fun replaceFavorites(items: List<SyncFavoriteSnapshotItem>) {
            appliedFavorites = items
        }
    }

    private class RecordingConfigurationSnapshotProvider(
        override val key: String,
        private val value: JsonElement,
    ) : ConfigurationSnapshotProvider {
        var appliedValue: JsonElement? = null

        override suspend fun read(): JsonElement = value

        override suspend fun apply(value: JsonElement) {
            appliedValue = value
        }
    }
}

private fun List<DeviceSettingEntry>.valueOf(key: String) =
    first { it.key == key }.value

private fun Settings.readEntryTimestamps(): Map<String, Long> =
    Json.decodeFromString(
        MapSerializer(String.serializer(), Long.serializer()),
        getString(SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS, "{}"),
    )
