package com.folderspan.pro.domain.usecase

import strings.AppStrings

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.DeviceIdentity
import com.folderspan.pro.core.network.runtimeDeviceIdentity
import com.folderspan.pro.domain.model.*
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SettingsUtils.SettingValueType
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlin.time.Clock

class DeviceSettingsSyncService(
    private val repository: SettingRepository,
    private val deviceIdentityProvider: () -> DeviceIdentity = ::runtimeDeviceIdentity,
    private val requestHeaderDeviceKeyProvider: () -> String? = { null },
    private val syncSnapshots: SyncSnapshots? = null,
    private val configurationSnapshots: List<ConfigurationSnapshotProvider> = emptyList(),
    private val timestampProvider: () -> Long = ::currentTimestampMillis,
    private val onSettingsApplied: () -> Unit = {},
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
) {
    suspend fun sync(settings: Settings, token: String) {
        val pullTimestamp = currentSyncTimestamp(settings)
        val pendingResult = flushPendingUploads(settings, token)
        if (pendingResult.failed.isNotEmpty()) return
        if (pendingResult.hasUploadedChanges) return
        pullMissingRemoteSettings(settings, token, timestampOverride = pullTimestamp)
        uploadDataEncryptionKey(settings, token)
    }

    suspend fun manualSync(
        settings: Settings,
        token: String,
        categories: Set<ManualSyncCategory>,
    ): ManualSyncResult {
        if (categories.isEmpty()) {
            return ManualSyncResult()
        }

        val pullTimestampFallback = currentSyncTimestamp(settings)
        val succeeded = mutableSetOf<ManualSyncCategory>()
        val failed = mutableMapOf<ManualSyncCategory, String>()
        val pending = pendingUploadKeys(settings)
        var hasUploadedChanges = false
        if (ManualSyncCategory.Settings in categories) {
            pending.settingKeys.forEach { key ->
                val result = uploadSettingInternal(settings, key, token)
                if (result is ApiResult.Success) {
                    succeeded += ManualSyncCategory.Settings
                    hasUploadedChanges = true
                } else if (result is ApiResult.Failure) {
                    failed[ManualSyncCategory.Settings] = result.message
                }
            }
        }
        pending.snapshotKeys
            .filter { key -> key.category in categories }
            .forEach { key ->
                val result = uploadSnapshot(settings, token, key)
                if (result is ApiResult.Success) {
                    succeeded += key.category
                    hasUploadedChanges = true
                } else if (result is ApiResult.Failure) {
                    failed[key.category] = result.message
                }
            }
        if (failed.isEmpty() && !hasUploadedChanges) {
            pullMissingRemoteSettings(
                settings = settings,
                token = token,
                timestampOverride = pullTimestampFallback,
                categories = categories,
            )
        }
        return ManualSyncResult(succeeded = succeeded, failed = failed, hasUploadedChanges = hasUploadedChanges)
    }

    suspend fun flushPendingUploads(settings: Settings, token: String): ManualSyncResult {
        val pending = pendingUploadKeys(settings)
        val succeeded = mutableSetOf<ManualSyncCategory>()
        val failed = mutableMapOf<ManualSyncCategory, String>()

        pending.settingKeys.forEach { key ->
            val result = uploadSettingInternal(settings, key, token, markPendingOnFailure = false)
            if (result is ApiResult.Success) {
                removePendingSettingKey(settings, key)
                succeeded += ManualSyncCategory.Settings
            } else if (result is ApiResult.Failure) {
                failed[ManualSyncCategory.Settings] = result.message
            }
        }
        pending.snapshotKeys.forEach { key ->
            val result = uploadSnapshot(settings, token, key, markPendingOnFailure = false)
            if (result is ApiResult.Success) {
                removePendingSnapshotKey(settings, key)
                succeeded += key.category
            } else if (result is ApiResult.Failure) {
                failed[key.category] = result.message
            }
        }

        return ManualSyncResult(succeeded = succeeded, failed = failed)
    }

    fun pendingUploadKeys(settings: Settings): PendingUploadKeys =
        PendingUploadKeys(
            settingKeys = settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS)
                .filter { SettingsUtils.syncableSettingOrNull(it) != null }
                .toSet(),
            snapshotKeys = settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS)
                .mapNotNull { SyncSnapshotKey.fromRemoteKey(it) }
                .toSet(),
        )

    suspend fun uploadConfigurationSnapshots(settings: Settings, token: String) {
        uploadDeviceConfigurationSnapshot(settings, token)
        uploadRoleConfigurationSnapshot(settings, token)
        uploadNetworkConfigurationSnapshot(settings, token)
        uploadWebRtcRoomsConfigurationSnapshot(settings, token)
        uploadSyncTasksConfigurationSnapshot(settings, token)
    }

    suspend fun uploadDeviceConfigurationSnapshot(settings: Settings, token: String) {
        uploadConfigurationSnapshot(settings, token, ProConfigurationSnapshotKeys.DEVICES)
    }

    suspend fun uploadRoleConfigurationSnapshot(settings: Settings, token: String) {
        uploadConfigurationSnapshot(settings, token, ProConfigurationSnapshotKeys.ROLES)
    }

    suspend fun uploadNetworkConfigurationSnapshot(settings: Settings, token: String) {
        uploadConfigurationSnapshot(settings, token, ProConfigurationSnapshotKeys.NETWORKS)
    }

    suspend fun uploadWebRtcRoomsConfigurationSnapshot(settings: Settings, token: String) {
        uploadConfigurationSnapshot(settings, token, ProConfigurationSnapshotKeys.WEB_RTC_ROOMS)
    }

    suspend fun uploadSyncTasksConfigurationSnapshot(settings: Settings, token: String) {
        uploadConfigurationSnapshot(settings, token, ProConfigurationSnapshotKeys.SYNC_TASKS)
    }

    private suspend fun uploadConfigurationSnapshot(settings: Settings, token: String, key: String) {
        uploadSnapshot(settings, token, SyncSnapshotKey.fromRemoteKey(key) ?: return)
    }

    private suspend fun uploadSnapshot(
        settings: Settings,
        token: String,
        snapshotKey: SyncSnapshotKey,
        markPendingOnFailure: Boolean = true,
    ): JsonResult {
        val targetId = currentTargetId() ?: return ApiResult.Failure(AppStrings.ui_device_missing_available_sync_target)
        if (snapshotKey == SyncSnapshotKey.Networks || snapshotKey == SyncSnapshotKey.WebRtc) {
            when (val dekResult = uploadDataEncryptionKey(settings, token)) {
                is ApiResult.Failure -> {
                    if (markPendingOnFailure) addPendingSnapshotKey(settings, snapshotKey)
                    return dekResult
                }
                else -> Unit
            }
        }
        val entry = snapshotEntry(snapshotKey) ?: return ApiResult.Failure(AppStrings.ui_this_sync_category_currently_unavailable)
        return when (val result = repository.saveDeviceSetting(targetId = targetId, entry = entry, token = token)) {
            is ApiResult.Success -> {
                updateLocalSyncTimestamp(settings, result.data, snapshotKey.remoteKey)
                removePendingSnapshotKey(settings, snapshotKey)
                result
            }

            is ApiResult.Failure -> {
                if (markPendingOnFailure) addPendingSnapshotKey(settings, snapshotKey)
                result
            }
        }
    }

    suspend fun uploadSetting(settings: Settings, key: String, token: String) {
        uploadSettingInternal(settings, key, token)
    }

    private suspend fun uploadSettingInternal(
        settings: Settings,
        key: String,
        token: String,
        markPendingOnFailure: Boolean = true,
    ): JsonResult {
        val targetId = currentTargetId() ?: return ApiResult.Failure(AppStrings.ui_device_missing_available_sync_target)
        val syncable = SettingsUtils.syncableSettingOrNull(key) ?: return ApiResult.Failure(AppStrings.ui_this_setting_does_not_support_synchronization)
        if (!settings.hasKey(syncable.key)) return ApiResult.Failure(AppStrings.ui_this_setting_has_not_yet_been_written_locally)

        return when (
            val result = repository.saveDeviceSetting(
                targetId = targetId,
                entry = DeviceSettingEntry(
                    key = syncable.key,
                    value = settings.readJsonValue(syncable),
                ),
                token = token,
            )
        ) {
            is ApiResult.Success -> {
                updateLocalSyncTimestamp(settings, result.data, syncable.key)
                removePendingSettingKey(settings, syncable.key)
                result
            }

            is ApiResult.Failure -> {
                if (markPendingOnFailure) addPendingSettingKey(settings, syncable.key)
                result
            }
        }
    }

    suspend fun uploadBookmarkSnapshot(settings: Settings, token: String) {
        uploadSnapshot(settings, token, SyncSnapshotKey.Bookmarks)
    }

    suspend fun uploadFavoriteSnapshot(settings: Settings, token: String) {
        uploadSnapshot(settings, token, SyncSnapshotKey.Favorites)
    }

    suspend fun uploadEditorSearchHistorySnapshot(settings: Settings, token: String) {
        uploadSnapshot(settings, token, SyncSnapshotKey.EditorSearchHistory)
    }

    suspend fun pullMissingRemoteSettings(
        settings: Settings,
        token: String,
        timestampOverride: Long? = null,
        categories: Set<ManualSyncCategory>? = null,
    ) {
        val targetId = currentTargetId() ?: return
        val timestamp = timestampOverride ?: currentSyncTimestamp(settings)
        when (val result = repository.listDeviceSettings(targetId = targetId, timestamp = timestamp, token = token)) {
            is ApiResult.Success -> applyRemoteEntries(
                settings = settings,
                entries = result.data,
                categories = categories,
                fallbackTimestamp = timestamp,
            )
            is ApiResult.Failure -> Unit
        }
    }

    suspend fun pullAllRemoteSettingsForTarget(
        settings: Settings,
        targetId: String,
        token: String,
    ): JsonResult {
        val normalizedTargetId = targetId.trim().takeIf { it.isNotEmpty() }
            ?: return ApiResult.Failure(AppStrings.ui_this_settings_snapshot_cannot_be_used_try_another_one)
        return when (
            val result = repository.listDeviceSettings(
                targetId = normalizedTargetId,
                timestamp = InitialSettingsSyncTimestamp,
                token = token,
            )
        ) {
            is ApiResult.Success -> {
                applyRemoteEntries(
                    settings = settings,
                    entries = result.data,
                    fallbackTimestamp = InitialSettingsSyncTimestamp,
                    ignoreStoredTimestamps = true,
                )
                ApiResult.Success(JsonNull)
            }

            is ApiResult.Failure -> result
        }
    }

    private suspend fun applyRemoteEntries(
        settings: Settings,
        entries: List<DeviceSettingEntry>,
        categories: Set<ManualSyncCategory>? = null,
        fallbackTimestamp: Long = currentSyncTimestamp(settings),
        ignoreStoredTimestamps: Boolean = false,
    ) {
        val entryTimestamps = settings.readEntryTimestamps()
        val dekKey = ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY
        val filteredEntries = buildList {
            entries.filter { entry -> entry.key == dekKey }.forEach(::add)
            entries.filter { entry ->
                entry.key != dekKey &&
                    (categories == null || entry.syncCategoryOrNull() in categories)
            }.forEach(::add)
        }
        val appliedTimestamps = mutableMapOf<String, Long>()
        var settingsApplied = false
        filteredEntries.forEach { entry ->
            val timestampKey = entry.syncTimestampKeyOrNull()
            if (timestampKey != null && !ignoreStoredTimestamps) {
                val entryUpdatedAt = entry.updatedAt
                if (entryUpdatedAt != null && entryUpdatedAt <= fallbackTimestamp) {
                    return@forEach
                }
            }
            if (entry.applySnapshotIfNeeded()) {
                entry.recordAppliedTimestamp(appliedTimestamps)
                return@forEach
            }
            if (entry.applyConfigurationSnapshotIfNeeded()) {
                entry.recordAppliedTimestamp(appliedTimestamps)
                return@forEach
            }
            val syncable = SettingsUtils.syncableSettingOrNull(entry.key) ?: return@forEach
            if (settings.writeJsonValue(syncable, entry.value)) {
                settingsApplied = true
                entry.recordAppliedTimestamp(appliedTimestamps)
            }
        }
        val maxUpdatedAt = filteredEntries.maxOfUpdatedAtOrNull()
        maxUpdatedAt?.let {
            settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, it)
        }
        if (appliedTimestamps.isNotEmpty()) {
            settings.writeEntryTimestamps(entryTimestamps + appliedTimestamps)
        }
        if (settingsApplied) {
            onSettingsApplied()
        }
    }

    private fun currentTargetId(): String? =
        requestHeaderDeviceKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: deviceIdentityProvider().key.trim().takeIf { it.isNotEmpty() }

    private fun currentSyncTimestamp(settings: Settings): Long =
        settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, InitialSettingsSyncTimestamp)
            .coerceAtLeast(InitialSettingsSyncTimestamp)

    private suspend fun snapshotEntry(snapshotKey: SyncSnapshotKey): DeviceSettingEntry? {
        val value = when (snapshotKey) {
            SyncSnapshotKey.Bookmarks -> {
                val snapshots = syncSnapshots ?: return null
                json.encodeToJsonElement(snapshots.readBookmarks())
            }

            SyncSnapshotKey.Favorites -> {
                val snapshots = syncSnapshots ?: return null
                json.encodeToJsonElement(snapshots.readFavorites())
            }

            SyncSnapshotKey.EditorSearchHistory -> {
                val snapshots = syncSnapshots ?: return null
                json.encodeToJsonElement(snapshots.readEditorSearchHistory())
            }

            else -> {
                val provider = configurationSnapshots.firstOrNull { item -> item.key == snapshotKey.remoteKey }
                    ?: return null
                val payload = provider.read()
                if (payload is JsonNull) return null
                payload
            }
        }
        return DeviceSettingEntry(key = snapshotKey.remoteKey, value = value)
    }

    private suspend fun uploadDataEncryptionKey(settings: Settings, token: String): JsonResult {
        val targetId = currentTargetId() ?: return ApiResult.Failure(AppStrings.ui_device_missing_available_sync_target)
        val encoded = DataEncryptionKey.encodedForSync() ?: return ApiResult.Success(JsonNull)
        return when (
            val result = repository.saveDeviceSetting(
                targetId = targetId,
                entry = DeviceSettingEntry(
                    key = ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY,
                    value = JsonPrimitive(encoded),
                ),
                token = token,
            )
        ) {
            is ApiResult.Success -> {
                updateLocalSyncTimestamp(settings, result.data, ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY)
                result
            }

            is ApiResult.Failure -> result
        }
    }

    private fun updateLocalSyncTimestamp(settings: Settings, response: JsonElement, remoteKey: String) {
        val timestamp = response.syncUpdatedAtOrNull() ?: timestampProvider()
        val currentGlobalTimestamp = currentSyncTimestamp(settings)
        val nextGlobalTimestamp = maxOf(currentGlobalTimestamp, timestamp)
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, nextGlobalTimestamp)
        val entryTimestamps = settings.readEntryTimestamps()
        val currentEntryTimestamp = entryTimestamps[remoteKey.syncTimestampKey()] ?: InitialSettingsSyncTimestamp
        val nextEntryTimestamp = maxOf(currentEntryTimestamp, timestamp)
        settings.writeEntryTimestamps(
            entryTimestamps + (remoteKey.syncTimestampKey() to nextEntryTimestamp),
        )
    }

    private fun addPendingSettingKey(settings: Settings, key: String) {
        settings.writeStringSet(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS) + key,
        )
    }

    private fun removePendingSettingKey(settings: Settings, key: String) {
        settings.writeStringSet(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
            settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS) - key,
        )
    }

    private fun addPendingSnapshotKey(settings: Settings, key: SyncSnapshotKey) {
        settings.writeStringSet(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS,
            settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS) + key.remoteKey,
        )
    }

    private fun removePendingSnapshotKey(settings: Settings, key: SyncSnapshotKey) {
        settings.writeStringSet(
            SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS,
            settings.readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS) - key.remoteKey,
        )
    }

    private fun Settings.readJsonValue(syncable: SettingsUtils.SyncableSetting): JsonElement =
        when (syncable.valueType) {
            SettingValueType.String -> JsonPrimitive(getString(syncable.key, syncable.defaultValue))
            SettingValueType.Boolean -> JsonPrimitive(getBoolean(syncable.key, syncable.defaultValue.toBooleanStrictOrNull() ?: false))
            SettingValueType.Int -> JsonPrimitive(getInt(syncable.key, syncable.defaultValue.toIntOrNull() ?: 0))
            SettingValueType.Long -> JsonPrimitive(getLong(syncable.key, syncable.defaultValue.toLongOrNull() ?: 0L))
            SettingValueType.StringList -> stringListJsonValue(getString(syncable.key, syncable.defaultValue))
        }

    private fun Settings.writeJsonValue(syncable: SettingsUtils.SyncableSetting, value: JsonElement): Boolean {
        return when (syncable.valueType) {
            SettingValueType.String -> value.stringOrNull()?.let { putString(syncable.key, it) } != null
            SettingValueType.Boolean -> value.booleanOrNull()?.let { putBoolean(syncable.key, it) } != null
            SettingValueType.Int -> value.intOrNull()?.let { putInt(syncable.key, it) } != null
            SettingValueType.Long -> value.longOrNull()?.let { putLong(syncable.key, it) } != null
            SettingValueType.StringList -> value.stringListOrNull()?.let { putString(syncable.key, json.encodeToString(it)) } != null
        }
    }

    private fun stringListJsonValue(raw: String): JsonArray =
        JsonArray(
            runCatching { json.decodeFromString<List<String>>(raw) }
                .getOrDefault(emptyList())
                .map { JsonPrimitive(it) },
        )

    private suspend fun DeviceSettingEntry.applySnapshotIfNeeded(): Boolean {
        val snapshots = syncSnapshots ?: return key.isSnapshotKey()
        when (key) {
            SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT -> {
                val remoteItems = runCatching {
                    json.decodeFromJsonElement<List<SyncBookmarkSnapshotItem>>(value)
                }.getOrDefault(emptyList())
                if (remoteItems.isNotEmpty()) {
                    snapshots.replaceBookmarks(snapshots.readBookmarks().mergeBookmarks(remoteItems))
                }
                return true
            }

            SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT -> {
                val remoteItems = runCatching {
                    json.decodeFromJsonElement<List<SyncFavoriteSnapshotItem>>(value)
                }.getOrDefault(emptyList())
                if (remoteItems.isNotEmpty()) {
                    snapshots.replaceFavorites(snapshots.readFavorites().mergeFavorites(remoteItems))
                }
                return true
            }

            SettingsUtils.KEY_EDITOR_SEARCH_HISTORY_SYNC_SNAPSHOT -> {
                val remoteItems = runCatching {
                    json.decodeFromJsonElement<List<com.folderspan.editor.EditorSearchHistoryEntry>>(value)
                }.getOrDefault(emptyList())
                snapshots.replaceEditorSearchHistory(remoteItems)
                return true
            }

            else -> return false
        }
    }

    private suspend fun DeviceSettingEntry.applyConfigurationSnapshotIfNeeded(): Boolean {
        val provider = configurationSnapshots.firstOrNull { item -> item.key == key }
        if (provider != null) {
            provider.apply(value)
            return true
        }
        return key.isConfigurationSnapshotKey()
    }
}

enum class ManualSyncCategory {
    Settings,
    Bookmarks,
    Favorites,
    Devices,
    Roles,
    Networks,
    WebRtc,
    SyncTasks,
    EditorSearchHistory,
}

enum class SyncSnapshotKey(
    val remoteKey: String,
    val category: ManualSyncCategory,
) {
    Bookmarks(SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT, ManualSyncCategory.Bookmarks),
    Favorites(SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT, ManualSyncCategory.Favorites),
    EditorSearchHistory(
        SettingsUtils.KEY_EDITOR_SEARCH_HISTORY_SYNC_SNAPSHOT,
        ManualSyncCategory.EditorSearchHistory,
    ),
    Devices(ProConfigurationSnapshotKeys.DEVICES, ManualSyncCategory.Devices),
    Roles(ProConfigurationSnapshotKeys.ROLES, ManualSyncCategory.Roles),
    Networks(ProConfigurationSnapshotKeys.NETWORKS, ManualSyncCategory.Networks),
    WebRtc(ProConfigurationSnapshotKeys.WEB_RTC_ROOMS, ManualSyncCategory.WebRtc),
    SyncTasks(ProConfigurationSnapshotKeys.SYNC_TASKS, ManualSyncCategory.SyncTasks);

    companion object {
        fun fromRemoteKey(key: String): SyncSnapshotKey? =
            entries.firstOrNull { it.remoteKey == key }
    }
}

data class PendingUploadKeys(
    val settingKeys: Set<String> = emptySet(),
    val snapshotKeys: Set<SyncSnapshotKey> = emptySet(),
)

data class ManualSyncResult(
    val succeeded: Set<ManualSyncCategory> = emptySet(),
    val failed: Map<ManualSyncCategory, String> = emptyMap(),
    internal val hasUploadedChanges: Boolean = succeeded.isNotEmpty(),
)

private fun String.isSnapshotKey(): Boolean =
    this == SettingsUtils.KEY_BOOKMARKS_SYNC_SNAPSHOT ||
        this == SettingsUtils.KEY_FAVORITES_SYNC_SNAPSHOT ||
        this == SettingsUtils.KEY_EDITOR_SEARCH_HISTORY_SYNC_SNAPSHOT

private fun String.isConfigurationSnapshotKey(): Boolean =
    this == ProConfigurationSnapshotKeys.DEVICES ||
        this == ProConfigurationSnapshotKeys.ROLES ||
        this == ProConfigurationSnapshotKeys.NETWORKS ||
        this == ProConfigurationSnapshotKeys.WEB_RTC_ROOMS ||
        this == ProConfigurationSnapshotKeys.SYNC_TASKS ||
        this == ProConfigurationSnapshotKeys.DATA_ENCRYPTION_KEY

private fun DeviceSettingEntry.syncCategoryOrNull(): ManualSyncCategory? =
    SyncSnapshotKey.fromRemoteKey(key)?.category
        ?: SettingsUtils.syncableSettingOrNull(key)?.let { ManualSyncCategory.Settings }

private fun String.syncTimestampKey(): String =
    if (SettingsUtils.syncableSettingOrNull(this) != null) {
        this
    } else {
        this
    }

private fun DeviceSettingEntry.syncTimestampKeyOrNull(): String? =
    key.takeIf { syncCategoryOrNull() != null }?.syncTimestampKey()

private fun DeviceSettingEntry.recordAppliedTimestamp(target: MutableMap<String, Long>) {
    val timestampKey = syncTimestampKeyOrNull() ?: return
    val updatedAt = updatedAt ?: return
    val current = target[timestampKey]
    if (current == null || updatedAt > current) {
        target[timestampKey] = updatedAt
    }
}

private fun Settings.readEntryTimestamps(): Map<String, Long> =
    runCatching {
        Json.decodeFromString(
            MapSerializer(String.serializer(), Long.serializer()),
            getString(SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS, "{}"),
        )
    }.getOrDefault(emptyMap())
        .mapKeys { (key, _) -> key.trim() }
        .filterKeys { it.isNotEmpty() }

private fun Settings.writeEntryTimestamps(values: Map<String, Long>) {
    if (values.isEmpty()) {
        remove(SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS)
    } else {
        putString(
            SettingsUtils.KEY_SETTINGS_SYNC_ENTRY_TIMESTAMPS,
            Json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                values.entries
                    .sortedBy { (key, _) -> key }
                    .associate { (key, value) -> key to value },
            ),
        )
    }
}

private fun Settings.readStringSet(key: String): Set<String> =
    runCatching {
        Json.decodeFromString<List<String>>(getString(key, "[]"))
    }.getOrDefault(emptyList())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun Settings.writeStringSet(key: String, values: Set<String>) {
    if (values.isEmpty()) {
        remove(key)
    } else {
        putString(key, Json.encodeToString(values.sorted()))
    }
}

private fun List<SyncBookmarkSnapshotItem>.mergeBookmarks(
    remoteItems: List<SyncBookmarkSnapshotItem>,
): List<SyncBookmarkSnapshotItem> =
    buildList {
        val seenKeys = mutableSetOf<String>()
        this@mergeBookmarks.forEach { item ->
            if (seenKeys.add(item.bookmarkSyncKey())) add(item)
        }
        remoteItems.forEach { item ->
            if (seenKeys.add(item.bookmarkSyncKey())) add(item.copy(sort = size + 1L))
        }
    }.mapIndexed { index, item -> item.copy(sort = index + 1L) }

private fun SyncBookmarkSnapshotItem.bookmarkSyncKey(): String =
    "${type.name}\u0000$path"

private fun List<SyncFavoriteSnapshotItem>.mergeFavorites(
    remoteItems: List<SyncFavoriteSnapshotItem>,
): List<SyncFavoriteSnapshotItem> =
    buildList {
        val seenKeys = mutableSetOf<String>()
        this@mergeFavorites.forEach { item ->
            if (seenKeys.add(item.favoriteSyncKey())) add(item)
        }
        remoteItems.forEach { item ->
            if (seenKeys.add(item.favoriteSyncKey())) add(item)
        }
    }

private fun SyncFavoriteSnapshotItem.favoriteSyncKey(): String =
    "${protocol.name}\u0000${protocolId.orEmpty()}\u0000$path"

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.booleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonElement.intOrNull(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonElement.longOrNull(): Long? =
    (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonElement.stringListOrNull(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonElement.syncUpdatedAtOrNull(): Long? {
    val obj = this as? JsonObject ?: return null
    return obj.longValue("updatedAt")
        ?: obj.objectValue("data")?.syncUpdatedAtOrNull()
        ?: obj.arrayValue("data")?.maxOfUpdatedAtOrNull()
        ?: obj.arrayValue("records")?.maxOfUpdatedAtOrNull()
        ?: obj.arrayValue("items")?.maxOfUpdatedAtOrNull()
        ?: obj.objectValue("data")?.arrayValue("records")?.maxOfUpdatedAtOrNull()
        ?: obj.objectValue("data")?.arrayValue("items")?.maxOfUpdatedAtOrNull()
        ?: obj.objectValue("data")?.arrayValue("list")?.maxOfUpdatedAtOrNull()
}

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name) as? JsonObject

private fun JsonObject.arrayValue(name: String): JsonArray? =
    get(name) as? JsonArray

private fun JsonObject.longValue(name: String): Long? =
    (get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

private fun List<DeviceSettingEntry>.maxOfUpdatedAtOrNull(): Long? =
    mapNotNull { it.updatedAt }
        .filter { it > 0L }
        .maxOrNull()

private fun JsonArray.maxOfUpdatedAtOrNull(): Long? =
    mapNotNull { it.syncUpdatedAtOrNull() }
        .filter { it > 0L }
        .maxOrNull()

private fun currentTimestampMillis(): Long =
    Clock.System.now().toEpochMilliseconds()

private const val InitialSettingsSyncTimestamp = 1L
