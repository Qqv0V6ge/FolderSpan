package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.WebRtcRoom
import com.folderspan.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class WebRtcRoomState(
    private val database: FolderSpanDatabase
) {
    val rooms = mutableStateListOf<WebRtcRoomProfile>()

    private var hasLoaded = false

    suspend fun loadPersisted() {
        if (hasLoaded) return
        hasLoaded = true
        refreshRooms()
    }

    suspend fun refreshRooms() {
        val persisted = try {
            withContext(Dispatchers.Default) {
                database.webRtcRoomQueries.selectAll().executeAsListAwait().map { item -> item.toProfile() }
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_load_webrtc_room_arg0.format(arg0 = (e.message).toString()))
            emptyList()
        }

        rooms.clear()
        rooms.addAll(persisted)
    }

    fun findRoomById(id: Long): WebRtcRoomProfile? {
        return rooms.firstOrNull { item -> item.id == id }
    }

    suspend fun addRoom(input: WebRtcRoomInput): Long? {
        ensureLoaded()
        val normalized = input.normalized()
        if (!normalized.canSave()) return null

        val timestamp = Clock.System.now().toEpochMilliseconds()
        val sortOrder = (rooms.maxOfOrNull { item -> item.sortOrder } ?: -1L) + 1L
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

    suspend fun updateRoom(id: Long, input: WebRtcRoomInput): Boolean {
        ensureLoaded()
        val normalized = input.normalized()
        return normalized.canSave() && try {
            withContext(Dispatchers.Default) {
                database.webRtcRoomQueries.updateById(
                    name = normalized.name,
                    wssUrl = normalized.wssUrl,
                    roomId = normalized.roomId,
                    stunUrl = normalized.stunUrl,
                    turnUrl = normalized.turnUrl,
                    turnUsername = normalized.turnUsername,
                    turnPassword = encodePassword(normalized.turnPassword),
                    source = normalized.source.name,
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

    suspend fun togglePinned(id: Long): Boolean {
        ensureLoaded()
        val room = findRoomById(id) ?: return false

        return setPinned(listOf(id), pinned = !room.pinned) > 0
    }

    suspend fun deleteRoom(id: Long): Boolean {
        ensureLoaded()
        return deleteRooms(listOf(id)) > 0
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean): Int {
        ensureLoaded()
        val targetIds = ids.toSet()
        if (targetIds.isEmpty()) return 0

        val targetRooms = rooms.filter { item ->
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

    suspend fun deleteRooms(ids: Collection<Long>): Int {
        ensureLoaded()
        val targetIds = ids.toSet()
        if (targetIds.isEmpty()) return 0

        val existingIds = rooms.asSequence()
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
            rooms.removeAll { item -> item.id in targetIds }
            SyncSnapshotChangeNotifier.onWebRtcConfigurationChanged()
            existingIds.size
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_batch_deletion_webrtc_rooms_failed_arg0.format(arg0 = (e.message).toString()))
            0
        }
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
                    source = input.source.name,
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
            source = WebRtcRoomSource.valueOf(source),
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
