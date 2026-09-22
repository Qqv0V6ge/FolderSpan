package com.folderspan.pro.data.mapper

import com.folderspan.data.main.webrtc.OfficialWebRtcRoomPage
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.officialWebRtcRoomProfile
import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.pro.data.remote.dto.WebRtcRoomWriteRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

internal fun WebRtcRoomInput.toWriteRequest(): WebRtcRoomWriteRequest {
    val normalized = normalized()
    return WebRtcRoomWriteRequest(
        name = normalized.name,
        stunUrl = normalized.stunUrl,
        turnUrl = normalized.turnUrl,
        turnUsername = normalized.turnUsername,
        turnPassword = normalized.turnPassword,
    )
}

internal fun JsonElement.toOfficialWebRtcRoomPage(page: Int, pageSize: Int): OfficialWebRtcRoomPage {
    val data = apiDataOrSelf() as? JsonObject
    val rooms = (data?.get("rooms") as? JsonArray)
        ?.mapNotNull { item -> (item as? JsonObject)?.toOfficialWebRtcRoomProfile(includeTurnPassword = false) }
        .orEmpty()
    return OfficialWebRtcRoomPage(
        rooms = rooms,
        total = data?.intValue("total") ?: rooms.size,
        page = page,
        pageSize = pageSize,
    )
}

internal fun JsonElement.toOfficialWebRtcRoomProfile(): WebRtcRoomProfile? {
    val data = apiDataOrSelf() as? JsonObject ?: this as? JsonObject
    return data?.toOfficialWebRtcRoomProfile()
}

private fun JsonObject.toOfficialWebRtcRoomProfile(includeTurnPassword: Boolean = true): WebRtcRoomProfile? {
    val roomId = stringOrEmpty("roomId").ifBlank { return null }
    val name = stringOrEmpty("name").ifBlank { return null }
    return officialWebRtcRoomProfile(
        name = name,
        roomId = roomId,
        stunUrl = stringOrEmpty("stunUrl"),
        turnUrl = stringOrEmpty("turnUrl"),
        turnUsername = stringOrEmpty("turnUsername"),
        turnPassword = if (includeTurnPassword) stringOrEmpty("turnPassword") else "",
        createdAt = epochMillis("createdAt"),
        updatedAt = epochMillis("updatedAt"),
        maxDevices = intValue("maxDevices") ?: 0,
    )
}

private fun JsonObject.stringOrEmpty(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.epochMillis(name: String): Long {
    val content = (get(name) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (content.isEmpty()) return 0L
    content.toLongOrNull()?.let { value ->
        return if (value in 1 until 10_000_000_000L) value * 1000L else value
    }
    return runCatching { Instant.parse(content).toEpochMilliseconds() }.getOrDefault(0L)
}
