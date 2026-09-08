package com.folderspan.pro.presentation.screen.sync

import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.pro.domain.usecase.SyncSnapshotKey
import com.folderspan.utils.SettingsChangeSync
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProChangeSyncRegistrationTest {

    @AfterTest
    fun tearDown() {
        SettingsChangeSync.setHandler(null)
        SyncSnapshotChangeNotifier.setBookmarkHandler(null)
        SyncSnapshotChangeNotifier.setFavoriteHandler(null)
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler(null)
        SyncSnapshotChangeNotifier.setDeviceConfigurationHandler(null)
        SyncSnapshotChangeNotifier.setRoleConfigurationHandler(null)
        SyncSnapshotChangeNotifier.setNetworkConfigurationHandler(null)
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler(null)
        SyncSnapshotChangeNotifier.setSyncTasksConfigurationHandler(null)
        SessionManager.initialize(InMemoryAuthSessionStore())
    }

    @Test
    fun settingChangeUsesSelectedManualSyncCategories() = runTest {
        val settings = MapSettings()
        settings.putInt(SettingsUtils.KEY_FILE_SHARE_PORT, 13000)
        settings.putString(
            SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES,
            Json.encodeToString(
                listOf(
                    ManualSyncCategory.Bookmarks.name,
                    ManualSyncCategory.Roles.name,
                ),
            ),
        )
        SessionManager.initialize(InMemoryAuthSessionStore(AuthSession(accessToken = "token-value")))
        val calls = mutableListOf<Set<ManualSyncCategory>>()

        registerProDeviceSettingChangeSync(settings, this) { _, categories ->
            calls += categories
        }

        SettingsChangeSync.onSettingChanged(SettingsUtils.KEY_FILE_SHARE_PORT)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(setOf(ManualSyncCategory.Bookmarks, ManualSyncCategory.Roles)),
            calls,
        )
        assertEquals(
            listOf(SettingsUtils.KEY_FILE_SHARE_PORT),
            Json.decodeFromString<List<String>>(
                settings.getString(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SETTING_KEYS, "[]"),
            ),
        )
    }

    @Test
    fun snapshotChangesUseSelectedManualSyncCategories() = runTest {
        val settings = MapSettings()
        settings.putString(
            SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES,
            Json.encodeToString(
                listOf(
                    ManualSyncCategory.Settings.name,
                    ManualSyncCategory.Networks.name,
                ),
            ),
        )
        SessionManager.initialize(InMemoryAuthSessionStore(AuthSession(accessToken = "token-value")))
        val calls = mutableListOf<Set<ManualSyncCategory>>()

        registerProSnapshotChangeSync(settings, this) { _, categories ->
            calls += categories
        }

        SyncSnapshotChangeNotifier.onBookmarksChanged()
        SyncSnapshotChangeNotifier.onRoleConfigurationChanged()
        SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
        SyncSnapshotChangeNotifier.onEditorSearchHistoryChanged()
        testScheduler.advanceTimeBy(TEST_SNAPSHOT_UPLOAD_DEBOUNCE_MS)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                setOf(ManualSyncCategory.Settings, ManualSyncCategory.Networks),
                setOf(ManualSyncCategory.Settings, ManualSyncCategory.Networks),
                setOf(ManualSyncCategory.Settings, ManualSyncCategory.Networks),
                setOf(ManualSyncCategory.Settings, ManualSyncCategory.Networks),
            ),
            calls,
        )
        assertEquals(
            listOf(
                SyncSnapshotKey.Bookmarks.remoteKey,
                SyncSnapshotKey.Roles.remoteKey,
                SyncSnapshotKey.WebRtc.remoteKey,
                SyncSnapshotKey.EditorSearchHistory.remoteKey,
            ).sorted(),
            Json.decodeFromString<List<String>>(
                settings.getString(SettingsUtils.KEY_SETTINGS_SYNC_PENDING_SNAPSHOT_KEYS, "[]"),
            ),
        )
    }
}

private const val TEST_SNAPSHOT_UPLOAD_DEBOUNCE_MS = 300L
