package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.SignalingDevice
import com.folderspan.service.webrtc.signaling.asTargetDevice
import com.folderspan.service.webrtc.signaling.withOfficialIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfficialWebRtcIdentityTest {
    @Test
    fun identicalDeviceIdsHaveIndependentTrustKeysAndRetainTheirWireTargets() {
        val first = SignalingDevice(id = "shared-id", userUuid = "550e8400-e29b-41d4-a716-446655440001")
        val second = first.copy(userUuid = "550e8400-e29b-41d4-a716-446655440002")
        val firstPeer = assertNotNull(first.withOfficialIdentity())
        val secondPeer = assertNotNull(second.withOfficialIdentity())
        assertNotEquals(first.id, firstPeer.id)
        assertNotEquals(firstPeer.id, secondPeer.id)
        assertEquals(first, firstPeer.asTargetDevice())
        assertEquals(second, secondPeer.asTargetDevice())
        assertNull(first.copy(userUuid = null).withOfficialIdentity())
    }
}
