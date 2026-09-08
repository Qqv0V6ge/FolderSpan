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
    fun sourceFilterMatchesOtherAndOfficialRooms() {
        assertTrue(WebRtcRoomSourceFilter.Other.matches(WebRtcRoomSource.Other))
        assertFalse(WebRtcRoomSourceFilter.Other.matches(WebRtcRoomSource.Official))

        assertTrue(WebRtcRoomSourceFilter.Official.matches(WebRtcRoomSource.Official))
        assertFalse(WebRtcRoomSourceFilter.Official.matches(WebRtcRoomSource.Other))
    }

    @Test
    fun deleteConfirmationUsesSnackbarActionCopy() {
        assertEquals(AppStrings.ui_delete, WebRtcRoomDeleteActionLabel)
        assertEquals(AppStrings.ui_test_web_rtc_room_manage_screen_confirm_deleting_office, webRtcRoomDeleteConfirmMessage("Office"))
        assertEquals(AppStrings.ui_test_web_rtc_room_manage_screen_confirm_deleting_3_selected_rooms, webRtcRoomBatchDeleteConfirmMessage(3))
    }
}
