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
import com.folderspan.data.main.webrtc.OfficialWebRtcRoomPage
import com.folderspan.data.main.webrtc.WebRtcOfficialGateway
import com.folderspan.data.main.webrtc.WebRtcOfficialGatewayProvider
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.data.main.webrtc.WebRtcShareCodeException
import com.folderspan.data.main.webrtc.WebRtcOfficialRooms
import com.folderspan.data.main.webrtc.WebRtcOfficialRoomsClient
import com.folderspan.data.main.webrtc.WebRtcRoomMembership
import com.folderspan.data.main.webrtc.WebRtcRoomMembershipAction
import com.folderspan.data.main.webrtc.WebRtcRoomMembershipState
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.data.main.webrtc.officialWebRtcRoomProfile
import com.folderspan.db.*
import com.folderspan.service.webrtc.signaling.generateRoomId
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.*
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.prefs.Preferences
import kotlin.test.*

class WebRtcRoomStateTest {
    @Test
    fun pinningARoomRemovedBeforeRefreshReturnsZeroWithoutNotifying() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val id = assertNotNull(state.addRoom(WebRtcRoomInput("Local", "wss://fixture.invalid", generateRoomId(), "", "", "", "", WebRtcRoomSource.Other)))
        database.webRtcRoomQueries.deleteById(id).awaitDatabaseReady()
        var changes = 0
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler { changes++ }
        try {
            assertEquals(0, state.setPinned(listOf(id), true))
            assertEquals(0, changes)
        } finally {
            SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler(null)
            settings.clear()
        }
    }

    @Test
    fun deletingARoomRemovedBeforeRefreshReturnsZeroWithoutNotifying() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val id = assertNotNull(state.addRoom(WebRtcRoomInput("Local", "wss://fixture.invalid", generateRoomId(), "", "", "", "", WebRtcRoomSource.Other)))
        database.webRtcRoomQueries.deleteById(id).awaitDatabaseReady()
        var changes = 0
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler { changes++ }
        try {
            assertEquals(0, state.deleteRooms(listOf(id)))
            assertEquals(0, changes)
        } finally {
            SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler(null)
            settings.clear()
        }
    }

    @Test
    fun editingARemovedRoomDoesNotReportSuccessOrEnqueueChanges() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val input = WebRtcRoomInput("Local", "wss://fixture.invalid", generateRoomId(), "", "", "", "fixture-turn", WebRtcRoomSource.Other)
        assertNotNull(state.addRoom(input))
        val oldRoom = state.rooms.single()
        assertTrue(state.deleteRoom(oldRoom))
        assertNotNull(state.addRoom(input.copy(name = "Replacement", roomId = generateRoomId())))
        val replacement = database.webRtcRoomQueries.selectAll().executeAsListAwait().single()
        var changes = 0
        SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler { changes++ }
        try {
            assertFalse(state.updateRoom(oldRoom, input.copy(name = "Edited")))
            assertEquals(0, changes)
            assertEquals(replacement, database.webRtcRoomQueries.selectAll().executeAsListAwait().single())
        } finally {
            SyncSnapshotChangeNotifier.setWebRtcConfigurationHandler(null)
            settings.clear()
        }
    }

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
        )
        assertTrue(state.updateRoom(roomId, updatedRoom))

        val updatedRow = database.webRtcRoomQueries.selectById(roomId).executeAsOneAwait()
        assertEquals(AppStrings.ui_test_web_rtc_room_state_updated_room, updatedRow.name)
        assertEquals(WebRtcRoomSource.Other.name, updatedRow.source)
        assertEquals(WebRtcRoomSource.Other, state.rooms.single().source)
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
            source = WebRtcRoomSource.Other.name,
            pinned = 0L,
            sortOrder = 0L,
            createdAt = 1L,
            updatedAt = 1L
        ).awaitDatabaseReady()

        val state = WebRtcRoomState(database)
        state.loadPersisted()

        assertEquals(1, state.rooms.size)
        assertEquals(AppStrings.ui_test_web_rtc_room_state_there_is_a_room, state.rooms.single().name)
        assertEquals(WebRtcRoomSource.Other, state.rooms.single().source)
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

    @Test
    fun leftoverOfficialRowsAreRemovedOnLoad() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        database.webRtcRoomQueries.insert(
            name = AppStrings.ui_test_web_rtc_room_state_there_is_a_room,
            wssUrl = "wss://official.example/ws",
            roomId = generateRoomId(),
            stunUrl = "",
            turnUrl = "",
            turnUsername = "",
            turnPassword = "",
            source = WebRtcRoomSource.Official.name,
            pinned = 0L,
            sortOrder = 0L,
            createdAt = 1L,
            updatedAt = 1L
        ).awaitDatabaseReady()
        database.webRtcRoomQueries.insert(
            name = AppStrings.ui_test_web_rtc_room_state_common_room,
            wssUrl = "wss://other.example/ws",
            roomId = generateRoomId(),
            stunUrl = "",
            turnUrl = "",
            turnUsername = "",
            turnPassword = "",
            source = WebRtcRoomSource.Other.name,
            pinned = 0L,
            sortOrder = 1L,
            createdAt = 1L,
            updatedAt = 1L
        ).awaitDatabaseReady()

        val state = WebRtcRoomState(database)
        state.loadPersisted()

        assertEquals(listOf(WebRtcRoomSource.Other), state.rooms.map { item -> item.source })
        assertEquals(1, database.webRtcRoomQueries.countAll().executeAsOneAwait())
        assertEquals(
            WebRtcRoomSource.Other.name,
            database.webRtcRoomQueries.selectAll().executeAsListAwait().single().source,
        )
    }

    @Test
    fun officialRoomsComeFromRemoteClientAndAreNotPersisted() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val previousClient = WebRtcOfficialRooms.client
        val remoteRoom = officialWebRtcRoomProfile(
            name = "Remote Office",
            roomId = "official-room-1",
            turnPassword = "turn-secret",
        )
        val client = FakeOfficialRoomsClient(
            listPages = listOf(
                OfficialWebRtcRoomPage(
                    rooms = listOf(remoteRoom),
                    total = 1,
                    page = 1,
                    pageSize = WebRtcOfficialRooms.DefaultPageSize,
                ),
            ),
        )
        WebRtcOfficialRooms.client = client
        try {
            val createdId = assertNotNull(
                state.addRoom(
                    WebRtcRoomInput(
                        name = "Created Office",
                        wssUrl = "",
                        roomId = "",
                        stunUrl = "",
                        turnUrl = "",
                        turnUsername = "",
                        turnPassword = "created-secret",
                        source = WebRtcRoomSource.Official,
                    )
                )
            )
            assertEquals(0L, createdId)
            assertEquals(1, client.created.size)
            assertEquals("Created Office", state.rooms.single().name)
            assertEquals("", state.rooms.single().turnPassword)
            assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())
            val createdLive = assertNotNull(state.getOfficialRoom("created-official"))
            assertEquals("created-secret", createdLive.turnPassword)
            assertEquals("", state.rooms.single().turnPassword)

            state.loadOfficialRooms(reset = true)
            assertEquals(listOf("official-room-1"), state.rooms.map { item -> item.roomId })
            assertEquals("", state.rooms.single().turnPassword)
            val listedLive = assertNotNull(state.getOfficialRoom("official-room-1"))
            assertEquals("turn-secret", listedLive.turnPassword)
            assertEquals("", state.rooms.single().turnPassword)
            assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())

            assertTrue(state.deleteRoom(state.rooms.single()))
            assertEquals(listOf("official-room-1"), client.deleted)
            assertTrue(state.rooms.none { item -> item.source == WebRtcRoomSource.Official })
        } finally {
            WebRtcOfficialRooms.client = previousClient
        }
    }

    @Test
    fun officialListFailureDoesNotPersistRooms() = runBlocking {
        val settings = createIsolatedSettings()
        val database = createInMemoryDatabase(settings)
        val state = WebRtcRoomState(database)
        val previousClient = WebRtcOfficialRooms.client
        WebRtcOfficialRooms.client = FakeOfficialRoomsClient(
            listError = IllegalStateException(AppStrings.ui_log_in_before_connecting_official_webrtc_room),
        )
        try {
            state.loadOfficialRooms(reset = true)
            assertEquals(AppStrings.ui_log_in_before_connecting_official_webrtc_room, state.officialError?.message)
            assertTrue(state.rooms.none { item -> item.source == WebRtcRoomSource.Official })
            assertEquals(0, database.webRtcRoomQueries.countAll().executeAsOneAwait())
        } finally {
            WebRtcOfficialRooms.client = previousClient
        }
    }

    @Test
    fun cancelledOfficialLoadsReleaseProgressAndAllowAnotherLoad() = runBlocking {
        val state = WebRtcRoomState(createInMemoryDatabase(createIsolatedSettings()))
        val first = officialWebRtcRoomProfile("First", generateRoomId())
        val second = officialWebRtcRoomProfile("Second", generateRoomId())
        val client = FakeOfficialRoomsClient(listPages = listOf(
            OfficialWebRtcRoomPage(listOf(first), 2, 1, 1),
            OfficialWebRtcRoomPage(listOf(second), 2, 2, 1),
        ))
        val previousClient = WebRtcOfficialRooms.client
        var pause = true
        WebRtcOfficialRooms.client = object : WebRtcOfficialRoomsClient by client {
            override suspend fun listRooms(page: Int, pageSize: Int): Result<OfficialWebRtcRoomPage> {
                if (pause) awaitCancellation()
                return client.listRooms(page, pageSize)
            }
        }
        try {
            val initial = launch(start = CoroutineStart.UNDISPATCHED) { state.loadOfficialRooms() }
            assertTrue(state.officialIsLoading)
            initial.cancelAndJoin()
            assertFalse(state.officialIsLoading)

            pause = false
            state.loadOfficialRooms()
            pause = true
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { state.loadOfficialRooms() }
            assertEquals(PageRefreshState.Refreshing, state.officialRefreshState)
            refresh.cancelAndJoin()
            assertEquals(PageRefreshState.Idle, state.officialRefreshState)

            val append = launch(start = CoroutineStart.UNDISPATCHED) { state.loadOfficialRooms(reset = false) }
            assertEquals(PageAppendState.Loading, state.officialAppendState)
            append.cancelAndJoin()
            assertEquals(PageAppendState.Idle, state.officialAppendState)
            pause = false
            state.loadOfficialRooms(reset = false)
            assertEquals(setOf(first.roomId, second.roomId), state.rooms.map { it.roomId }.toSet())
            assertEquals(PageAppendState.End, state.officialAppendState)
        } finally {
            WebRtcOfficialRooms.client = previousClient
        }
    }

    @Test
    fun officialExportLoadsOwnerDetailsInsteadOfUsingTheCachedSummary() = runBlocking<Unit> {
        val state = WebRtcRoomState(createInMemoryDatabase(createIsolatedSettings()))
        val roomId = generateRoomId()
        val summary = officialWebRtcRoomProfile("旧名称", roomId, turnUrl = "turn:old.test:3478")
        val current = summary.copy(
            name = "当前房间", turnUrl = "turn:one.test:3478;turns:two.test:5349",
            turnUsername = " user ", turnPassword = " pass ",
        )
        val previousClient = WebRtcOfficialRooms.client
        val previousGateway = WebRtcOfficialGateway.provider
        val gateway = "wss://official.test/api/v1/webrtc/ws"
        WebRtcOfficialRooms.client = FakeOfficialRoomsClient(
            listPages = listOf(OfficialWebRtcRoomPage(listOf(summary), 1, 1, 20)),
            details = listOf(current),
        )
        WebRtcOfficialGateway.provider = object : WebRtcOfficialGatewayProvider {
            override fun websocketUrl(): String = gateway
            override fun websocketHeaders(): Result<Map<String, String>> =
                error("Room export must never ask for authentication headers")
        }
        try {
            state.loadOfficialRooms()
            assertEquals("", state.rooms.single().turnPassword)
            val code = state.exportOfficialRoomCode(roomId).getOrThrow()
            val decoded = WebRtcShareCode.decodeOfficialRoom(code, gateway)
            assertEquals("当前房间", decoded.name)
            assertEquals(listOf("turn:one.test:3478", "turns:two.test:5349"), decoded.turnUrls)
            assertEquals(" user ", decoded.turnUsername)
            assertEquals(" pass ", decoded.turnPassword)
            kotlin.test.assertFailsWith<WebRtcShareCodeException> {
                WebRtcShareCode.decodeOfficialRoom(code, "wss://changed.test/api/v1/webrtc/ws")
            }
        } finally {
            WebRtcOfficialRooms.client = previousClient
            WebRtcOfficialGateway.provider = previousGateway
        }
    }
}

private class FakeOfficialRoomsClient(
    private val listPages: List<OfficialWebRtcRoomPage> = emptyList(),
    private val listError: Throwable? = null,
    details: List<WebRtcRoomProfile> = emptyList(),
) : WebRtcOfficialRoomsClient {
    val created = mutableListOf<WebRtcRoomInput>()
    val deleted = mutableListOf<String>()
    private val roomsById = details.associateBy(WebRtcRoomProfile::roomId).toMutableMap()

    override suspend fun listRooms(page: Int, pageSize: Int): Result<OfficialWebRtcRoomPage> {
        listError?.let { return Result.failure(it) }
        val payload = listPages.firstOrNull { item -> item.page == page }
            ?: OfficialWebRtcRoomPage(emptyList(), total = 0, page = page, pageSize = pageSize)
        return Result.success(payload)
    }

    override suspend fun getRoom(roomId: String): Result<WebRtcRoomProfile> {
        listError?.let { return Result.failure(it) }
        roomsById[roomId]?.let { return Result.success(it) }
        listPages.asSequence()
            .flatMap { item -> item.rooms.asSequence() }
            .firstOrNull { item -> item.roomId == roomId }
            ?.let { return Result.success(it) }
        return Result.failure(IllegalStateException(AppStrings.ui_failed_load_webrtc_room_arg0.format(arg0 = roomId)))
    }

    override suspend fun createRoom(input: WebRtcRoomInput): Result<WebRtcRoomProfile> {
        created += input
        val profile = officialWebRtcRoomProfile(
            name = input.name,
            roomId = "created-official",
            stunUrl = input.stunUrl,
            turnUrl = input.turnUrl,
            turnUsername = input.turnUsername,
            turnPassword = input.turnPassword,
        )
        roomsById[profile.roomId] = profile
        return Result.success(profile)
    }

    override suspend fun getRoomConnection(roomId: String): Result<WebRtcRoomProfile> = getRoom(roomId)

    override suspend fun getMembership(roomId: String): Result<WebRtcRoomMembership?> = Result.success(null)

    override suspend fun listMemberships(
        roomId: String, state: WebRtcRoomMembershipState, page: Int,
    ): Result<List<WebRtcRoomMembership>> = Result.success(emptyList())

    override suspend fun changeMembership(
        roomId: String, version: Long, action: WebRtcRoomMembershipAction, applicantUserUuid: String?,
    ): Result<WebRtcRoomMembership> = error("Unexpected membership write")

    override suspend fun updateRoom(roomId: String, input: WebRtcRoomInput): Result<WebRtcRoomProfile> {
        return Result.success(
            officialWebRtcRoomProfile(
                name = input.name,
                roomId = roomId,
                stunUrl = input.stunUrl,
                turnUrl = input.turnUrl,
                turnUsername = input.turnUsername,
                turnPassword = input.turnPassword,
            )
        )
    }

    override suspend fun deleteRoom(roomId: String): Result<Unit> {
        deleted += roomId
        return Result.success(Unit)
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
