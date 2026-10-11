package com.folderspan.ui.screen.device

import strings.AppStrings

import com.folderspan.service.message.DEVICE_MESSAGE_MAX_BODY_BYTES
import com.folderspan.service.message.DeviceConversationSummary
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageTransport
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceMessagesScreenTest : ChineseLocalizationTest() {
    @Test
    fun conversationListIncludesOnlineAndOfflineHistoryPeers() {
        val summaries = listOf(
            DeviceConversationSummary("offline-peer", 200L, 3L),
            DeviceConversationSummary("both-peer", 100L, 0L),
        )
        val endpoints = setOf(
            endpoint("online-peer", DeviceMessageTransport.Session),
            endpoint("both-peer", DeviceMessageTransport.Session),
        )

        assertEquals(
            listOf("offline-peer", "both-peer", "online-peer"),
            buildDeviceMessagePeerIds(summaries, endpoints),
        )
    }

    @Test
    fun composerRequiresOnlineEndpointAndValidUtf8Length() {
        assertFalse(canSendDeviceMessage("hello", 5, endpointOnline = false, operationInProgress = false))
        assertFalse(canSendDeviceMessage("   ", 3, endpointOnline = true, operationInProgress = false))
        assertFalse(
            canSendDeviceMessage(
                "x",
                DEVICE_MESSAGE_MAX_BODY_BYTES + 1,
                endpointOnline = true,
                operationInProgress = false,
            ),
        )
        assertTrue(
            canSendDeviceMessage(
                "x",
                DEVICE_MESSAGE_MAX_BODY_BYTES,
                endpointOnline = true,
                operationInProgress = false,
            ),
        )
    }

    @Test
    fun conversationKeepsCurrentEndpointAndFallsBackWithoutASelector() {
        val firstSession = endpoint("peer", DeviceMessageTransport.Session, "first")
        val replacementSession = endpoint("peer", DeviceMessageTransport.Session, "replacement")

        assertEquals(
            firstSession,
            resolveDeviceMessageConversationEndpoint(
                firstSession,
                replacementSession,
                listOf(firstSession, replacementSession),
            ),
        )
        assertEquals(
            replacementSession,
            resolveDeviceMessageConversationEndpoint(firstSession, replacementSession, listOf(replacementSession)),
        )
        assertEquals(
            firstSession,
            resolveDeviceMessageConversationEndpoint(null, null, listOf(firstSession)),
        )
    }

    @Test
    fun composerCountsUnicodeCharactersInsteadOfUtf8Bytes() {
        assertEquals(3, deviceMessageCharacterCount(AppStrings.ui_test_device_messages_screen_you))
        assertEquals(0, deviceMessageCharacterCount(""))
    }

    @Test
    fun longMessageUsesExpandablePresentation() {
        assertFalse(shouldCollapseDeviceMessageBody("short"))
        assertTrue(shouldCollapseDeviceMessageBody("x".repeat(481)))
    }

    private fun endpoint(
        peerId: String,
        transport: DeviceMessageTransport,
        connectionId: String = "$peerId-${transport.name}",
    ) =
        DeviceMessageEndpointIdentity(
            peerDeviceId = peerId,
            transport = transport,
            connectionId = connectionId,
        )
}
