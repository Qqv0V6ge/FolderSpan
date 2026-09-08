package com.folderspan.pro.presentation.screen.sync

import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.di.AppServices
import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.pro.domain.usecase.SyncSnapshotKey
import com.folderspan.utils.SettingsChangeSync
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private const val SNAPSHOT_UPLOAD_DEBOUNCE_MS = 300L

private typealias ProManualSyncAction = suspend (Settings, Set<ManualSyncCategory>) -> Unit

fun registerProDeviceSettingChangeSync(
    settings: Settings,
    scope: CoroutineScope,
    servicesProvider: () -> AppServices = { AppServices(settings) },
) {
    registerProDeviceSettingChangeSync(settings, scope) { targetSettings, categories ->
        runProManualSync(targetSettings, categories, servicesProvider)
    }
}

internal fun registerProDeviceSettingChangeSync(
    settings: Settings,
    scope: CoroutineScope,
    manualSync: ProManualSyncAction,
) {
    SettingsChangeSync.setHandler { key ->
        val syncableKey = SettingsUtils.syncableSettingOrNull(key)?.key ?: return@setHandler
        if (SessionManager.currentToken().isNullOrBlank()) return@setHandler
        settings.addPendingSettingSyncKey(syncableKey)
        scope.launch {
            runCatching {
                manualSync(settings, settings.readManualDataSyncSelectedCategories())
            }
        }
    }
}

fun registerProSnapshotChangeSync(
    settings: Settings,
    scope: CoroutineScope,
    servicesProvider: () -> AppServices = { AppServices(settings) },
) {
    registerProSnapshotChangeSync(settings, scope) { targetSettings, categories ->
        runProManualSync(targetSettings, categories, servicesProvider)
    }
}

internal fun registerProSnapshotChangeSync(
    settings: Settings,
    scope: CoroutineScope,
    manualSync: ProManualSyncAction,
) {
    var bookmarkUploadJob: Job? = null
    var favoriteUploadJob: Job? = null
    var editorSearchHistoryUploadJob: Job? = null
    var deviceConfigurationUploadJob: Job? = null
    var roleConfigurationUploadJob: Job? = null
    var networkConfigurationUploadJob: Job? = null
    var webRtcConfigurationUploadJob: Job? = null
    var syncTasksConfigurationUploadJob: Job? = null

    SyncSnapshotChangeNotifier.setBookmarkHandler {
        bookmarkUploadJob?.cancel()
        bookmarkUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.Bookmarks, manualSync)
    }

    SyncSnapshotChangeNotifier.setFavoriteHandler {
        favoriteUploadJob?.cancel()
        favoriteUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.Favorites, manualSync)
    }

    SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler {
        editorSearchHistoryUploadJob?.cancel()
        editorSearchHistoryUploadJob = launchProSnapshotManualSync(
            settings,
            scope,
            SyncSnapshotKey.EditorSearchHistory,
            manualSync,
        )
    }

    SyncSnapshotChangeNotifier.setDeviceConfigurationHandler {
        deviceConfigurationUploadJob?.cancel()
        deviceConfigurationUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.Devices, manualSync)
    }

    SyncSnapshotChangeNotifier.setRoleConfigurationHandler {
        roleConfigurationUploadJob?.cancel()
        roleConfigurationUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.Roles, manualSync)
    }

    SyncSnapshotChangeNotifier.setNetworkConfigurationHandler {
        networkConfigurationUploadJob?.cancel()
        networkConfigurationUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.Networks, manualSync)
    }

    SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler {
        webRtcConfigurationUploadJob?.cancel()
        webRtcConfigurationUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.WebRtc, manualSync)
    }

    SyncSnapshotChangeNotifier.setSyncTasksConfigurationHandler {
        syncTasksConfigurationUploadJob?.cancel()
        syncTasksConfigurationUploadJob = launchProSnapshotManualSync(settings, scope, SyncSnapshotKey.SyncTasks, manualSync)
    }
}

private fun launchProSnapshotManualSync(
    settings: Settings,
    scope: CoroutineScope,
    snapshotKey: SyncSnapshotKey,
    manualSync: ProManualSyncAction,
): Job =
    scope.launch {
        delay(SNAPSHOT_UPLOAD_DEBOUNCE_MS.milliseconds)
        if (SessionManager.currentToken().isNullOrBlank()) return@launch
        settings.addPendingSnapshotSyncKey(snapshotKey)
        runCatching {
            manualSync(settings, settings.readManualDataSyncSelectedCategories())
        }
    }

private suspend fun runProManualSync(
    settings: Settings,
    categories: Set<ManualSyncCategory>,
    servicesProvider: () -> AppServices,
) {
    val token = SessionManager.currentToken()?.takeIf { it.isNotBlank() } ?: return
    val services = servicesProvider()
    try {
        services.deviceSettingsSyncService.manualSync(settings, token, categories)
    } finally {
        services.close()
    }
}

private fun Settings.addPendingSettingSyncKey(key: String) {
    writeStringSet(
        SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS,
        readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS) + key,
    )
}

private fun Settings.addPendingSnapshotSyncKey(snapshotKey: SyncSnapshotKey) {
    writeStringSet(
        SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS,
        readStringSet(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS) + snapshotKey.remoteKey,
    )
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
