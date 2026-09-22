package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcRoomManageScreenTest : ChineseLocalizationTest() {

    @Test
    fun searchMatchesRoomId() {
        assertTrue(
            webRtcRoomMatchesSearch(
                name = "Office",
                wssUrl = "wss://example.test/ws",
                roomId = "room-id-123",
                source = WebRtcRoomSource.Other,
                keyword = "room-id"
            )
        )
    }

    @Test
    fun searchMatchesOtherRoomSignalingAddress() {
        assertTrue(
            webRtcRoomMatchesSearch(
                name = "Office",
                wssUrl = "wss://other.example/ws",
                roomId = "room-id-123",
                source = WebRtcRoomSource.Other,
                keyword = "other.example"
            )
        )
    }

    @Test
    fun searchIgnoresOfficialRoomSignalingAddress() {
        assertFalse(
            webRtcRoomMatchesSearch(
                name = "Office",
                wssUrl = "wss://official.example/ws",
                roomId = "room-id-123",
                source = WebRtcRoomSource.Official,
                keyword = "official.example"
            )
        )
        assertTrue(
            webRtcRoomMatchesSearch(
                name = "Office",
                wssUrl = "wss://official.example/ws",
                roomId = "room-id-123",
                source = WebRtcRoomSource.Official,
                keyword = "Office"
            )
        )
    }

    @Test
    fun listSupportTextHidesOfficialSignalingAddress() {
        assertEquals(
            "wss://other.example/ws",
            webRtcRoomListSupportText(
                source = WebRtcRoomSource.Other,
                wssUrl = "wss://other.example/ws",
                lastError = null
            )
        )
        assertEquals(
            "",
            webRtcRoomListSupportText(
                source = WebRtcRoomSource.Official,
                wssUrl = "wss://official.example/ws",
                lastError = null
            )
        )
        assertEquals(
            AppStrings.webrtc_error_prefix + "timeout",
            webRtcRoomListSupportText(
                source = WebRtcRoomSource.Official,
                wssUrl = "wss://official.example/ws",
                lastError = "timeout"
            )
        )
    }

    @Test
    fun connectConfirmHidesOfficialSignalingAddress() {
        val otherMessage = webRtcRoomConnectConfirmMessage(
            name = "Office",
            wssUrl = "wss://other.example/ws",
            roomId = "room-id-123",
            source = WebRtcRoomSource.Other
        )
        assertTrue(otherMessage.contains(AppStrings.ui_signaling))
        assertTrue(otherMessage.contains("wss://other.example/ws"))

        val officialMessage = webRtcRoomConnectConfirmMessage(
            name = "Office",
            wssUrl = "wss://official.example/ws",
            roomId = "room-id-123",
            source = WebRtcRoomSource.Official
        )
        assertFalse(officialMessage.contains(AppStrings.ui_signaling))
        assertFalse(officialMessage.contains("wss://official.example/ws"))
        assertTrue(officialMessage.contains("room-id-123"))
        assertTrue(officialMessage.contains(WebRtcRoomSource.Official.label))
    }

    @Test
    fun drawerSubtitleHidesOfficialSignalingAddress() {
        assertEquals(
            "wss://other.example/ws",
            webRtcRoomDrawerSubtitle(
                source = WebRtcRoomSource.Other,
                wssUrl = "wss://other.example/ws"
            )
        )
        assertEquals(
            "",
            webRtcRoomDrawerSubtitle(
                source = WebRtcRoomSource.Official,
                wssUrl = "wss://official.example/ws"
            )
        )
    }

    @Test
    fun officialSourceIsVisibleOnlyWhenLoggedIn() {
        assertEquals(
            listOf(WebRtcRoomSource.Other),
            webRtcRoomSourceOptions(canUseOfficialGateway = false)
        )
        assertEquals(
            listOf(WebRtcRoomSource.Other, WebRtcRoomSource.Official),
            webRtcRoomSourceOptions(canUseOfficialGateway = true)
        )
        assertEquals(
            WebRtcRoomSource.Other,
            resolveWebRtcRoomSource(WebRtcRoomSource.Official, canUseOfficialGateway = false)
        )
        assertEquals(
            WebRtcRoomSource.Official,
            resolveWebRtcRoomSource(WebRtcRoomSource.Official, canUseOfficialGateway = true)
        )
        assertFalse(webRtcRoomShowsSourceSelector(webRtcRoomSourceOptions(canUseOfficialGateway = false)))
        assertTrue(webRtcRoomShowsSourceSelector(webRtcRoomSourceOptions(canUseOfficialGateway = true)))
    }

    @Test
    fun sourceSelectionShowsOnlyMatchingRooms() {
        assertTrue(webRtcRoomMatchesSelectedSource(WebRtcRoomSource.Other, WebRtcRoomSource.Other))
        assertFalse(webRtcRoomMatchesSelectedSource(WebRtcRoomSource.Official, WebRtcRoomSource.Other))

        assertTrue(webRtcRoomMatchesSelectedSource(WebRtcRoomSource.Official, WebRtcRoomSource.Official))
        assertFalse(webRtcRoomMatchesSelectedSource(WebRtcRoomSource.Other, WebRtcRoomSource.Official))
    }

    @Test
    fun deleteConfirmationUsesSnackbarActionCopy() {
        assertEquals(AppStrings.ui_delete, WebRtcRoomDeleteActionLabel)
        assertEquals(AppStrings.ui_test_web_rtc_room_manage_screen_confirm_deleting_office, webRtcRoomDeleteConfirmMessage("Office"))
        assertEquals(AppStrings.ui_test_web_rtc_room_manage_screen_confirm_deleting_3_selected_rooms, webRtcRoomBatchDeleteConfirmMessage(3))
    }
}
