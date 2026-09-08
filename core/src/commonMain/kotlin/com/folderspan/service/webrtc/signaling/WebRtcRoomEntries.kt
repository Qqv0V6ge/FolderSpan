package com.folderspan.service.webrtc.signaling

private val webRtcConfigValueSeparatorRegex = Regex("[,;\\n\\r]+")

data class WebRtcRoomEntry(
    val index: Int,
    val wssUrl: String,
    val roomId: String
) {
    val entryKey: String = buildEntryKey(wssUrl = wssUrl, roomId = roomId)
}

data class WebRtcRoomEntries(
    val items: List<WebRtcRoomEntry>,
    val hostCount: Int,
    val roomCount: Int,
    val pairingError: String? = null
)

fun parseWebRtcConfigValues(raw: String): List<String> {
    return raw
        .split(webRtcConfigValueSeparatorRegex)
        .map { item -> item.trim() }
        .filter { item -> item.isNotEmpty() }
}

fun parseWebRtcRoomEntries(wssUrl: String, roomId: String): WebRtcRoomEntries {
    val hosts = parseWebRtcConfigValues(wssUrl)
    val rooms = parseWebRtcConfigValues(roomId)
    if (hosts.isEmpty() && rooms.isEmpty()) {
        return WebRtcRoomEntries(
            items = emptyList(),
            hostCount = 0,
            roomCount = 0
        )
    }

    val normalizedHosts = hosts.ifEmpty { listOf("") }
    val normalizedRooms = rooms.ifEmpty { listOf("") }
    val items = buildList {
        normalizedHosts.forEach { host ->
            normalizedRooms.forEach { room ->
                add(
                    WebRtcRoomEntry(
                        index = size,
                        wssUrl = host,
                        roomId = room
                    )
                )
            }
        }
    }.distinctBy { item -> item.entryKey }

    return WebRtcRoomEntries(
        items = items,
        hostCount = hosts.size,
        roomCount = rooms.size
    )
}

fun buildEntryKey(wssUrl: String, roomId: String): String {
    return "$wssUrl::$roomId"
}
