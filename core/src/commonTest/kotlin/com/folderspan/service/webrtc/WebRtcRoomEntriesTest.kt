package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.parseWebRtcRoomEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebRtcRoomEntriesTest {
    @Test
    fun parseWebRtcRoomEntriesBuildsHostRoomCombinations() {
        val entries = parseWebRtcRoomEntries(
            wssUrl = "wss://a.example/ws, wss://b.example/ws",
            roomId = "room-a, room-b, room-c"
        )

        assertEquals(2, entries.hostCount)
        assertEquals(3, entries.roomCount)
        assertNull(entries.pairingError)
        assertEquals(
            listOf(
                "wss://a.example/ws::room-a",
                "wss://a.example/ws::room-b",
                "wss://a.example/ws::room-c",
                "wss://b.example/ws::room-a",
                "wss://b.example/ws::room-b",
                "wss://b.example/ws::room-c",
            ),
            entries.items.map { item -> item.entryKey }
        )
    }

    @Test
    fun parseWebRtcRoomEntriesKeepsBlankSideAsSingleDimension() {
        val entries = parseWebRtcRoomEntries(
            wssUrl = "wss://a.example/ws, wss://b.example/ws",
            roomId = ""
        )

        assertEquals(
            listOf(
                "wss://a.example/ws::",
                "wss://b.example/ws::",
            ),
            entries.items.map { item -> item.entryKey }
        )
    }
}
