package com.folderspan.utils

import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.settings.SettingsState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsChangeSyncTest {
    @AfterTest
    fun tearDown() {
        SettingsChangeSync.setHandler(null)
        SyncSnapshotChangeNotifier.setBookmarkHandler(null)
        SyncSnapshotChangeNotifier.setFavoriteHandler(null)
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler(null)
    }

    @Test
    fun settingsStateNotifiesOnlySyncableUserSettingChanges() {
        val changedKeys = mutableListOf<String>()
        val state = SettingsState(createInMemorySettings())
        SettingsChangeSync.setHandler { key -> changedKeys.add(key) }

        state.setFileSharePort(13000)
        state.setDynamicColorEnabled(false, sync = false)
        state.setRemoteOpenDownloadDirectory("/tmp")

        assertEquals(listOf(SettingsUtils.KEY_FILE_SHARE_PORT), changedKeys)
    }

    @Test
    fun drawerStateDoesNotNotifyWebRtcDrawerSettings() {
        val changedKeys = mutableListOf<String>()
        val drawerState = DrawerState(createInMemorySettings())
        SettingsChangeSync.setHandler { key -> changedKeys.add(key) }

        drawerState.updateShowWebRtc(true)
        drawerState.updateShowDevice(false)

        assertEquals(listOf(SettingsUtils.KEY_DRAWER_SHOW_DEVICE), changedKeys)
    }

    @Test
    fun syncSnapshotChangeNotifierEmitsBookmarkAndFavoriteChanges() {
        val changes = mutableListOf<String>()
        SyncSnapshotChangeNotifier.setBookmarkHandler { changes += "bookmarks" }
        SyncSnapshotChangeNotifier.setFavoriteHandler { changes += "favorites" }
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler { changes += "editor-search-history" }

        SyncSnapshotChangeNotifier.onBookmarksChanged()
        SyncSnapshotChangeNotifier.onFavoritesChanged()
        SyncSnapshotChangeNotifier.onEditorSearchHistoryChanged()

        assertEquals(listOf("bookmarks", "favorites", "editor-search-history"), changes)
    }
}
