package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folderspan.data.main.webrtc.WebRtcOfficialRooms
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.data.main.webrtc.withoutCachedTurnPassword
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.WebRtcRoom
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.toPageErrorState
import com.folderspan.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmName
import kotlin.time.Clock

class WebRtcRoomState(
    private val database: FolderSpanDatabase
) {
    val rooms = mutableStateListOf<WebRtcRoomProfile>()

    var officialIsLoading by mutableStateOf(false)
        private set
    var officialRefreshState by mutableStateOf<PageRefreshState>(PageRefreshState.Idle)
        private set
    var officialAppendState by mutableStateOf<PageAppendState>(PageAppendState.Idle)
        private set
    var officialError by mutableStateOf<PageErrorState?>(null)
        private set

    private val localRooms = mutableListOf<WebRtcRoomProfile>()
    private val officialRooms = mutableListOf<WebRtcRoomProfile>()
    private var officialPage = 0
    private var officialTotal = 0
    private var hasLoaded = false

    private val officialClient
        get() = WebRtcOfficialRooms.client

    val officialHasMore: Boolean
        get() = officialRooms.size < officialTotal

    suspend fun loadPersisted() {
        if (hasLoaded) return
        hasLoaded = true
        refreshRooms()
    }

    suspend fun refreshRooms() {
        val persisted = try {
            withContext(Dispatchers.Default) {
                database.webRtcRoomQueries.deleteBySource(WebRtcRoomSource.Official.name).awaitDatabaseReady()
                database.webRtcRoomQueries.selectAll().executeAsListAwait()
                    .map { item -> item.toProfile() }
                    .filter { item -> item.source == WebRtcRoomSource.Other }
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_load_webrtc_room_arg0.format(arg0 = (e.message).toString()))
            emptyList()
        }

        localRooms.clear()
        localRooms.addAll(persisted)
        publishRooms()
    }

    suspend fun loadOfficialRooms(reset: Boolean = true) {
        if (reset) {
            if (officialRooms.isEmpty()) {
                officialIsLoading = true
            } else {
                officialRefreshState = PageRefreshState.Refreshing
            }
            officialError = null
            officialAppendState = PageAppendState.Idle
        } else {
            if (!officialHasMore || officialAppendState is PageAppendState.Loading) return
            officialAppendState = PageAppendState.Loading
        }

        val page = if (reset) 1 else officialPage + 1
        val result = officialClient.listRooms(page, WebRtcOfficialRooms.DefaultPageSize)
        officialIsLoading = false
        officialRefreshState = PageRefreshState.Idle

        result.fold(
            onSuccess = { payload ->
                officialError = null
                officialPage = payload.page
                officialTotal = payload.total
                if (reset) {
                    officialRooms.clear()
                }
                officialRooms.removeAll { existing ->
                    payload.rooms.any { incoming -> incoming.roomId == existing.roomId }
                }
                officialRooms.addAll(payload.rooms.map { item -> item.withoutCachedTurnPassword() })
                officialAppendState = if (officialRooms.size >= officialTotal) {
                    PageAppendState.End
                } else {
                    PageAppendState.Idle
                }
                publishRooms()
            },
            onFailure = { error ->
                val pageError = error.toPageErrorState()
                if (reset && officialRooms.isEmpty()) {
                    officialError = pageError
                    officialAppendState = PageAppendState.Idle
                } else if (reset) {
                    officialRefreshState = PageRefreshState.Error(error.message)
                } else {
                    officialAppendState = PageAppendState.Error(error.message)
                }
            }
        )
    }

    fun findRoomById(id: Long): WebRtcRoomProfile? {
        return rooms.firstOrNull { item -> item.id == id && item.source == WebRtcRoomSource.Other }
    }

    fun findRoomByCatalogKey(catalogKey: String): WebRtcRoomProfile? {
        return rooms.firstOrNull { item -> item.catalogKey == catalogKey }
    }

    suspend fun getOfficialRoom(roomId: String): WebRtcRoomProfile? {
        val normalizedRoomId = roomId.trim()
        if (normalizedRoomId.isBlank()) return null
        return officialClient.getRoom(normalizedRoomId).fold(
            onSuccess = { profile ->
                upsertOfficialRoom(profile.withoutCachedTurnPassword())
                profile
            },
            onFailure = { error ->
                LogKit.w(AppStrings.ui_failed_load_webrtc_room_arg0.format(arg0 = (error.message).toString()))
                null
            }
        )
    }

    suspend fun addRoom(input: WebRtcRoomInput): Long? {
        ensureLoaded()
        val normalized = input.normalized()
        if (!normalized.canSave()) return null
        if (normalized.source == WebRtcRoomSource.Official) {
            return officialClient.createRoom(normalized).fold(
                onSuccess = { profile ->
                    upsertOfficialRoom(profile.withoutCachedTurnPassword())
                    officialTotal += 1
                    0L
                },
                onFailure = { error ->
                    LogKit.w(AppStrings.ui_failed_save_webrtc_room_arg0.format(arg0 = (error.message).toString()))
                    null
                }
            )
        }

        val timestamp = Clock.System.now().toEpochMilliseconds()
        val sortOrder = (localRooms.maxOfOrNull { item -> item.sortOrder } ?: -1L) + 1L
        val insertedId = insertRoomInternal(
            input = normalized,
            sortOrder = sortOrder,
            createdAt = timestamp,
            updatedAt = timestamp
        ) ?: return null
        refreshRooms()
        SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
        return insertedId
    }

    suspend fun updateRoom(room: WebRtcRoomProfile, input: WebRtcRoomInput): Boolean {
        ensureLoaded()
        val normalized = input.normalized()
        if (!normalized.canSave()) return false
        if (room.source == WebRtcRoomSource.Official || normalized.source == WebRtcRoomSource.Official) {
            return officialClient.updateRoom(room.roomId, normalized.copy(source = WebRtcRoomSource.Official)).fold(
                onSuccess = { profile ->
                    upsertOfficialRoom(profile.withoutCachedTurnPassword())
                    true
                },
                onFailure = { error ->
                    LogKit.w(AppStrings.ui_failed_update_webrtc_room_arg0.format(arg0 = (error.message).toString()))
                    false
                }
            )
        }
        return updateLocalRoom(room.id, normalized)
    }

    suspend fun updateRoom(id: Long, input: WebRtcRoomInput): Boolean {
        val room = findRoomById(id) ?: return false
        return updateRoom(room, input)
    }

    suspend fun togglePinned(id: Long): Boolean {
        ensureLoaded()
        val room = findRoomById(id) ?: return false
        return setPinned(listOf(id), pinned = !room.pinned) > 0
    }

    suspend fun deleteRoom(id: Long): Boolean {
        ensureLoaded()
        return deleteLocalRooms(listOf(id)) > 0
    }

    suspend fun deleteRoom(room: WebRtcRoomProfile): Boolean {
        ensureLoaded()
        return deleteRooms(listOf(room)) > 0
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean): Int {
        ensureLoaded()
        val targetIds = ids.toSet()
        if (targetIds.isEmpty()) return 0

        val targetRooms = localRooms.filter { item ->
            item.id in targetIds && item.pinned != pinned
        }
        if (targetRooms.isEmpty()) return 0

        return try {
            val updatedAt = Clock.System.now().toEpochMilliseconds()
            withContext(Dispatchers.Default) {
                targetRooms.forEach { room ->
                    database.webRtcRoomQueries.updatePinnedById(
                        pinned = if (pinned) 1L else 0L,
                        updatedAt = updatedAt,
                        id = room.id
                    ).awaitDatabaseReady()
                }
            }
            refreshRooms()
            SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
            targetRooms.size
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_batch_update_webrtc_room_top_status_failed_arg0.format(arg0 = (e.message).toString()))
            0
        }
    }

    suspend fun deleteRooms(roomsToDelete: Collection<WebRtcRoomProfile>): Int {
        ensureLoaded()
        if (roomsToDelete.isEmpty()) return 0
        val officialToDelete = roomsToDelete.filter { item -> item.source == WebRtcRoomSource.Official }
        val localToDelete = roomsToDelete.filter { item -> item.source == WebRtcRoomSource.Other }
        var deleted = 0
        officialToDelete.forEach { room ->
            val result = officialClient.deleteRoom(room.roomId)
            if (result.isSuccess) {
                officialRooms.removeAll { item -> item.roomId == room.roomId }
                officialTotal = (officialTotal - 1).coerceAtLeast(0)
                deleted += 1
            } else {
                LogKit.w(AppStrings.ui_batch_deletion_webrtc_rooms_failed_arg0.format(arg0 = (result.exceptionOrNull()?.message).toString()))
            }
        }
        if (officialToDelete.isNotEmpty()) {
            publishRooms()
        }
        deleted += deleteLocalRooms(localToDelete.map { item -> item.id })
        return deleted
    }

    @JvmName("deleteRoomsByIds")
    suspend fun deleteRooms(ids: Collection<Long>): Int {
        ensureLoaded()
        return deleteLocalRooms(ids)
    }

    private suspend fun deleteLocalRooms(ids: Collection<Long>): Int {
        val targetIds = ids.toSet()
        if (targetIds.isEmpty()) return 0

        val existingIds = localRooms.asSequence()
            .map { item -> item.id }
            .filter { id -> id in targetIds }
            .toList()
        if (existingIds.isEmpty()) return 0

        return try {
            withContext(Dispatchers.Default) {
                existingIds.forEach { id ->
                    database.webRtcRoomQueries.deleteById(id).awaitDatabaseReady()
                }
            }
            localRooms.removeAll { item -> item.id in targetIds }
            publishRooms()
            SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
            existingIds.size
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_batch_deletion_webrtc_rooms_failed_arg0.format(arg0 = (e.message).toString()))
            0
        }
    }

    private suspend fun updateLocalRoom(id: Long, normalized: WebRtcRoomInput): Boolean {
        return try {
            withContext(Dispatchers.Default) {
                database.webRtcRoomQueries.updateById(
                    name = normalized.name,
                    wssUrl = normalized.wssUrl,
                    roomId = normalized.roomId,
                    stunUrl = normalized.stunUrl,
                    turnUrl = normalized.turnUrl,
                    turnUsername = normalized.turnUsername,
                    turnPassword = encodePassword(normalized.turnPassword),
                    source = WebRtcRoomSource.Other.name,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    id = id
                ).awaitDatabaseReady()
            }
            refreshRooms()
            SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
            true
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_update_webrtc_room_arg0.format(arg0 = (e.message).toString()))
            false
        }
    }

    private fun upsertOfficialRoom(profile: WebRtcRoomProfile) {
        val listed = profile.withoutCachedTurnPassword()
        val index = officialRooms.indexOfFirst { item -> item.roomId == listed.roomId }
        if (index >= 0) {
            officialRooms[index] = listed
        } else {
            officialRooms.add(0, listed)
        }
        publishRooms()
    }

    private fun publishRooms() {
        rooms.clear()
        rooms.addAll(officialRooms)
        rooms.addAll(localRooms)
    }

    private suspend fun ensureLoaded() {
        if (!hasLoaded) {
            loadPersisted()
        }
    }

    private suspend fun insertRoomInternal(
        input: WebRtcRoomInput,
        sortOrder: Long,
        createdAt: Long,
        updatedAt: Long,
        validateBeforeSave: Boolean = true
    ): Long? {
        if (validateBeforeSave && !input.canSave()) {
            return null
        }
        if (input.source == WebRtcRoomSource.Official) {
            return null
        }

        return try {
            withContext(Dispatchers.Default) {
                database.webRtcRoomQueries.insert(
                    name = input.name,
                    wssUrl = input.wssUrl,
                    roomId = input.roomId,
                    stunUrl = input.stunUrl,
                    turnUrl = input.turnUrl,
                    turnUsername = input.turnUsername,
                    turnPassword = encodePassword(input.turnPassword),
                    source = WebRtcRoomSource.Other.name,
                    pinned = 0L,
                    sortOrder = sortOrder,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                ).awaitDatabaseReady()
                database.webRtcRoomQueries.lastInsertRowId().executeAsOneAwait()
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_save_webrtc_room_arg0.format(arg0 = (e.message).toString()))
            null
        }
    }

    private fun WebRtcRoom.toProfile(): WebRtcRoomProfile {
        return WebRtcRoomProfile(
            id = id,
            name = name,
            wssUrl = wssUrl,
            roomId = roomId,
            stunUrl = stunUrl,
            turnUrl = turnUrl,
            turnUsername = turnUsername,
            turnPassword = decodePassword(turnPassword),
            source = runCatching { WebRtcRoomSource.valueOf(source) }.getOrDefault(WebRtcRoomSource.Other),
            pinned = pinned != 0L,
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun encodePassword(password: String): String {
        if (password.isBlank()) return ""
        return SymmetricCrypto.encrypt(password)
    }

    private fun decodePassword(password: String): String {
        if (password.isBlank()) return ""
        return runCatching { SymmetricCrypto.decrypt(password) }.getOrElse { password }
    }
}
