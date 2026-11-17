package com.folderspan.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.snapshots.Snapshot
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.service.createDatabase
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.device.DeviceSettingsState
import com.folderspan.ui.state.file.FileBookmarkState
import com.folderspan.ui.state.file.FileFavoriteState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.ui.state.file.FileOperationState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.HomeState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.SyncState
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TempSyncTaskStore
import com.folderspan.ui.state.main.WebRtcRoomState
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SharedStateSnapshotInitializationTest(private val stateName: String) {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = createDatabase(driver)
    private val settings = PropertiesSettings(Properties())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        startKoin {
            modules(commonScreenModule, module {
                single<SqlDriver> { driver }
                single { database }
                single<Settings> { settings }
            })
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        driver.close()
        Dispatchers.resetMain()
    }

    @Test
    fun directlyConstructedSharedStateIsReadableFromAnOlderPageSnapshot() {
        val page = Snapshot.takeMutableSnapshot()
        try {
            val read = createReader()
            assertNotNull(page.enter { read() }, stateName)
        } finally {
            page.dispose()
        }
    }

    private fun <T> reader(state: T, read: T.() -> Any?): () -> Any? = { state.read() }

    private fun createReader(): () -> Any? = when (stateName) {
        "WebUiAttachment" -> reader(WebUiAttachment) {
            listOf(mode, showConnectionDialog, connected, hasSession, stale, sessionGeneration)
        }
        "TaskState" -> reader(TaskState()) { listOf(revision, tasks.toList()) }
        "SyncState" -> reader(SyncState(TempSyncTaskStore())) {
            listOf(revision, tasks.toList(), runHistory.toMap(), taskAttention.toMap())
        }
        "DeviceState" -> reader(DeviceState()) {
            listOf(devices.toList(), socketDevices.toList(), remoteDeviceConnections.toMap(),
                connectionRequest.toMap(), connectionRequestsMissingNotification.toMap(), shares.toList(),
                shareRequest.toMap(), allowDeviceShareConnection.toMap())
        }
        "NetworkState" -> reader(NetworkState(database)) { listOf(entries.toList(), connectedEntries.toList()) }
        "WebRtcRoomState" -> reader(WebRtcRoomState(database)) {
            listOf(rooms.toList(), officialIsLoading, officialRefreshState, officialAppendState, officialError)
        }
        "FileState" -> reader(FileState()) { listOf(fileAndFolder.toList(), dropOperationState.files.toList()) }
        "HomeState" -> reader(HomeState()) { checkedFileSimpleInfo.toList() }
        "FileShareState" -> reader(FileShareState()) {
            listOf(files.toList(), checkedFiles.toList(), incomingFiles, sendFile.toMap(), sendFileMessage.toMap(),
                authorizedLinkShareDevices.toMap(), linkShareSessions.toMap(), linkShareTickets.toMap(),
                pendingLinkShareUploadDevices.toList(), rejectedLinkShareUploadDevices.toList(),
                pendingLinkShareDevices.toList(), rejectedLinkShareDevices.toList(), shareToDevices.toMap(),
                linkShareDefaults, deviceRequestLog.toMap())
        }
        "FileFilterState" -> reader(FileFilterState(settings)) {
            listOf(filterFileTypes.toList(), filterFileExtensions.toList())
        }
        "FileFavoriteState" -> reader(FileFavoriteState()) { favorites.toList() }
        "FileBookmarkState" -> reader(
            FileBookmarkState(ScopedBookmarkRepository(database)) { error("Not used during initialization") },
        ) { listOf(bookmarks.toList(), activeScope, sourceAvailable) }
        "FileOperationState" -> reader(FileOperationState()) { files.toList() }
        "DeviceRoleState" -> reader(DeviceRoleState(database, driver)) { roles.toList() }
        "DevicePermissionState" -> reader(DevicePermissionState(database)) { permissions.toList() }
        "DeviceSettingsState" -> reader(DeviceSettingsState(database)) { devices.toList() }
        "SyncConditionRuns" -> reader(SyncState(TempSyncTaskStore()).waitingRunConditions) { toMap() }
        "NotificationState" -> reader(NotificationState()) {
            listOf(revision, notifications.toList(), ignoredBannerIds, pendingOpenRequestId)
        }
        "MainState(StateFlow)" -> reader(MainState()) { isExpandDrawer.value }
        else -> error("Unknown shared state: $stateName")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun states(): List<Array<String>> = listOf(
            "WebUiAttachment", "TaskState", "SyncState", "DeviceState", "NetworkState", "WebRtcRoomState",
            "FileState", "HomeState", "FileShareState", "FileFilterState", "FileFavoriteState", "FileBookmarkState",
            "FileOperationState", "DeviceRoleState", "DevicePermissionState", "DeviceSettingsState", "SyncConditionRuns",
            "NotificationState", "MainState(StateFlow)",
        ).map { arrayOf(it) }
    }
}
