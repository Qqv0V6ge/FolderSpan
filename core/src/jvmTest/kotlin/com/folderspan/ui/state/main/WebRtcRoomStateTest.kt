package com.folderspan.ui.state.main

import strings.AppStrings

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.db.*
import com.folderspan.service.webrtc.signaling.generateRoomId
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.*
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.prefs.Preferences
import kotlin.test.*

class WebRtcRoomStateTest {
    @Test
    fun addUpdateDeleteRoomPersistsRecords() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val changes = mutableListOf<String>()
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler { changes += "webrtc" }

        val initialRoom = WebRtcRoomInput(
            name = AppStrings.ui_test_web_rtc_room_state_testing_room,
            wssUrl = "wss://example.com/ws",
            roomId = generateRoomId(),
            stunUrl = "stun:example.com:3478",
            turnUrl = "turn:example.com:3478",
            turnUsername = "user-a",
            turnPassword = "secret-a",
            source = WebRtcRoomSource.Other,
        )

        val roomId = assertNotNull(state.addRoom(initialRoom))
        assertEquals(1, state.rooms.size)

        val savedRoom = database.webRtcRoomQueries.selectAll().executeAsListAwait().single()
        assertEquals(AppStrings.ui_test_web_rtc_room_state_testing_room, savedRoom.name)
        assertEquals(WebRtcRoomSource.Other.name, savedRoom.source)
        assertEquals(0L, savedRoom.pinned)
        assertNotEquals("secret-a", savedRoom.turnPassword)

        val updatedRoom = initialRoom.copy(
            name = AppStrings.ui_test_web_rtc_room_state_updated_room,
            roomId = generateRoomId(),
            turnPassword = "secret-b",
            source = WebRtcRoomSource.Official,
        )
        assertTrue(state.updateRoom(roomId, updatedRoom))

        val updatedRow = database.webRtcRoomQueries.selectById(roomId).executeAsOneAwait()
        assertEquals(AppStrings.ui_test_web_rtc_room_state_updated_room, updatedRow.name)
        assertEquals(WebRtcRoomSource.Official.name, updatedRow.source)
        assertEquals(WebRtcRoomSource.Official, state.rooms.single().source)
        assertTrue(state.rooms.single().roomIdValid)
        assertEquals("secret-b", state.rooms.single().turnPassword)

        assertTrue(state.deleteRoom(roomId))
        assertEquals(0, state.rooms.size)
        assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())
        assertEquals(listOf("webrtc", "webrtc", "webrtc"), changes)
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler(null)
    }

    @Test
    fun loadPersistedReadsSavedRooms() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val existingPassword = SymmetricCrypto.encrypt("persisted-secret")
        database.webRtcRoomQueries.insert(
            name = AppStrings.ui_test_web_rtc_room_state_there_is_a_room,
            wssUrl = "wss://persisted.example/ws",
            roomId = generateRoomId(),
            stunUrl = "stun:persisted.example:3478",
            turnUrl = "turn:persisted.example:3478",
            turnUsername = "persisted-user",
            turnPassword = existingPassword,
            source = WebRtcRoomSource.Official.name,
            pinned = 0L,
            sortOrder = 0L,
            createdAt = 1L,
            updatedAt = 1L
        ).awaitDatabaseReady()

        val state = WebRtcRoomState(database)
        state.loadPersisted()

        assertEquals(1, state.rooms.size)
        assertEquals(AppStrings.ui_test_web_rtc_room_state_there_is_a_room, state.rooms.single().name)
        assertEquals(WebRtcRoomSource.Official, state.rooms.single().source)
        assertEquals("persisted-secret", state.rooms.single().turnPassword)
        assertEquals(false, state.rooms.single().pinned)
    }

    @Test
    fun invalidRoomInputIsRejected() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)

        val result = state.addRoom(
            WebRtcRoomInput(
                name = AppStrings.ui_test_web_rtc_room_state_invalid_room,
                wssUrl = "wss://example.com/ws",
                roomId = "invalid-room-id",
                stunUrl = "",
                turnUrl = "",
                turnUsername = "",
                turnPassword = "",
                source = WebRtcRoomSource.Other,
            )
        )

        assertNull(result)
        assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())
    }

    @Test
    fun httpSignalingRoomCannotOmitRoomId() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)

        val result = state.addRoom(
            WebRtcRoomInput(
                name = AppStrings.ui_test_web_rtc_room_state_http_signaling,
                wssUrl = "http://127.0.0.1:12040",
                roomId = "",
                stunUrl = "",
                turnUrl = "",
                turnUsername = "",
                turnPassword = "",
                source = WebRtcRoomSource.Other,
            )
        )

        assertNull(result)
        assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())
    }

    @Test
    fun togglePinnedPersistsAndSortsPinnedRoomsFirst() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)

        val firstId = assertNotNull(
            state.addRoom(
                WebRtcRoomInput(
                    name = AppStrings.ui_test_web_rtc_room_state_common_room,
                    wssUrl = "wss://a.example/ws",
                    roomId = generateRoomId(),
                    stunUrl = "",
                    turnUrl = "",
                    turnUsername = "",
                    turnPassword = "",
                    source = WebRtcRoomSource.Other,
                )
            )
        )
        val secondId = assertNotNull(
            state.addRoom(
                WebRtcRoomInput(
                    name = AppStrings.ui_test_web_rtc_room_state_top_room,
                    wssUrl = "wss://b.example/ws",
                    roomId = generateRoomId(),
                    stunUrl = "",
                    turnUrl = "",
                    turnUsername = "",
                    turnPassword = "",
                    source = WebRtcRoomSource.Other,
                )
            )
        )

        assertTrue(state.togglePinned(secondId))

        assertEquals(listOf(secondId, firstId), state.rooms.map { item -> item.id })
        assertTrue(state.rooms.first().pinned)
        assertEquals(1L, database.webRtcRoomQueries.selectById(secondId).executeAsOneAwait().pinned)

        assertTrue(state.togglePinned(secondId))
        assertEquals(listOf(firstId, secondId), state.rooms.map { item -> item.id })
        assertEquals(0L, database.webRtcRoomQueries.selectById(secondId).executeAsOneAwait().pinned)
    }

    @Test
    fun batchPinAndDeleteOnlyAffectSelectedRooms() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)

        val firstId = assertNotNull(
            state.addRoom(
                WebRtcRoomInput(
                    name = AppStrings.ui_test_web_rtc_room_state_room_a,
                    wssUrl = "wss://a.example/ws",
                    roomId = generateRoomId(),
                    stunUrl = "",
                    turnUrl = "",
                    turnUsername = "",
                    turnPassword = "",
                    source = WebRtcRoomSource.Other,
                )
            )
        )
        val secondId = assertNotNull(
            state.addRoom(
                WebRtcRoomInput(
                    name = AppStrings.ui_test_web_rtc_room_state_room_b,
                    wssUrl = "wss://b.example/ws",
                    roomId = generateRoomId(),
                    stunUrl = "",
                    turnUrl = "",
                    turnUsername = "",
                    turnPassword = "",
                    source = WebRtcRoomSource.Other,
                )
            )
        )
        val thirdId = assertNotNull(
            state.addRoom(
                WebRtcRoomInput(
                    name = AppStrings.ui_test_web_rtc_room_state_room_c,
                    wssUrl = "wss://c.example/ws",
                    roomId = generateRoomId(),
                    stunUrl = "",
                    turnUrl = "",
                    turnUsername = "",
                    turnPassword = "",
                    source = WebRtcRoomSource.Other,
                )
            )
        )

        assertEquals(2, state.setPinned(listOf(firstId, thirdId), pinned = true))
        assertEquals(
            listOf(firstId, thirdId),
            state.rooms.filter { item -> item.pinned }.map { item -> item.id }
        )
        assertEquals(1L, database.webRtcRoomQueries.selectById(firstId).executeAsOneAwait().pinned)
        assertEquals(0L, database.webRtcRoomQueries.selectById(secondId).executeAsOneAwait().pinned)
        assertEquals(1L, database.webRtcRoomQueries.selectById(thirdId).executeAsOneAwait().pinned)

        assertEquals(1, state.setPinned(listOf(firstId, secondId), pinned = false))
        assertEquals(listOf(thirdId), state.rooms.filter { item -> item.pinned }.map { item -> item.id })

        assertEquals(2, state.deleteRooms(listOf(firstId, thirdId)))
        assertEquals(listOf(secondId), state.rooms.map { item -> item.id })
        assertEquals(1, database.webRtcRoomQueries.countAll().executeAsOneAwait())
    }
}

private fun createIsolatedSettings(): PreferencesSettings {
    val node = Preferences.userRoot().node("folderspan-test-${UUID.randomUUID()}")
    return PreferencesSettings(node)
}

private fun createInMemoryDatabase(settings: PreferencesSettings): FolderSpanDatabase {
    SettingsUtils.init(settings)
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(
            typeAdapter = deviceTypeAdapter,
        ),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
            categoryAdapter = deviceCategoryAdapter,
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = drawerBookmarkTypeAdapter,
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileRecentAdapter = FileRecent.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = fileFilterTypeAdapter,
            extensionsAdapter = listOfStringsAdapter
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = fileFilterSortAdapter,
            ignoreFilesAdapter = listOfStringsAdapter,
        )
    )
}

private val deviceTypeAdapter = object : ColumnAdapter<DeviceType, String> {
    override fun decode(databaseValue: String): DeviceType = DeviceType.valueOf(databaseValue)
    override fun encode(value: DeviceType): String = value.name
}

private val deviceConnectTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
    override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
    override fun encode(value: DeviceConnectType): String = value.name
}

private val deviceCategoryAdapter = object : ColumnAdapter<DeviceCategory, String> {
    override fun decode(databaseValue: String): DeviceCategory = DeviceCategory.valueOf(databaseValue)
    override fun encode(value: DeviceCategory): String = value.name
}

private val drawerBookmarkTypeAdapter = object : ColumnAdapter<DrawerBookmarkType, String> {
    override fun decode(databaseValue: String): DrawerBookmarkType = DrawerBookmarkType.valueOf(databaseValue)
    override fun encode(value: DrawerBookmarkType): String = value.name
}

private val fileProtocolAdapter = object : ColumnAdapter<FileProtocol, String> {
    override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
    override fun encode(value: FileProtocol): String = value.name
}

private val fileFilterTypeAdapter = object : ColumnAdapter<FileFilterType, String> {
    override fun decode(databaseValue: String): FileFilterType = FileFilterType.valueOf(databaseValue)
    override fun encode(value: FileFilterType): String = value.name
}

private val listOfStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> {
        return if (databaseValue.isEmpty()) {
            listOf()
        } else {
            databaseValue.split(",")
        }
    }

    override fun encode(value: List<String>): String = value.joinToString(separator = ",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}
