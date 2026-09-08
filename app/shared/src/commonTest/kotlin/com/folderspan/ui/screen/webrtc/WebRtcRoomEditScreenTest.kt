package com.folderspan.ui.screen.webrtc

import strings.AppStrings

import com.folderspan.data.main.webrtc.WebRtcRoomSource
import com.folderspan.service.webrtc.WebRtcRoomConnectionTestResult
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRtcRoomEditScreenTest : ChineseLocalizationTest() {

    @Test
    fun sourceOptionsHideOfficialWhenUserHasNotLoggedIn() {
        assertEquals(
            listOf(WebRtcRoomSource.Other),
            webRtcRoomSourceOptions(canUseOfficialGateway = false)
        )
    }

    @Test
    fun sourceOptionsShowOfficialWhenUserHasLoggedIn() {
        assertEquals(
            listOf(WebRtcRoomSource.Other, WebRtcRoomSource.Official),
            webRtcRoomSourceOptions(canUseOfficialGateway = true)
        )
    }

    @Test
    fun displayedConnectionFieldsRestoreOtherDraftAfterOfficialMode() {
        val fields = webRtcRoomDisplayedConnectionFields(
            source = WebRtcRoomSource.Other,
            otherWssUrl = "wss://custom.example/ws",
            otherRoomId = "custom-room-id",
            officialWssUrl = "wss://official.example/ws",
            officialRoomId = "official-room-id"
        )

        assertEquals("wss://custom.example/ws", fields.wssUrl)
        assertEquals("custom-room-id", fields.roomId)
    }

    @Test
    fun connectionFieldsAreHiddenForOfficialRooms() {
        assertTrue(webRtcRoomShowsConnectionFields(WebRtcRoomSource.Other))
        assertFalse(webRtcRoomShowsConnectionFields(WebRtcRoomSource.Official))
    }

    @Test
    fun connectionTestMessageMapsSuccess() {
        assertEquals(
            AppStrings.ui_connection_successful,
            webRtcRoomConnectionTestMessage(WebRtcRoomConnectionTestResult.Success)
        )
    }

    @Test
    fun connectionTestMessageMapsFetchFailureToBrowserHint() {
        assertEquals(
            AppStrings.ui_connection_failed_browser_cannot_access_target_service_check_endpoint,
            webRtcRoomConnectionTestMessage(
                WebRtcRoomConnectionTestResult.Failure("Failed to fetch")
            )
        )
    }
}
