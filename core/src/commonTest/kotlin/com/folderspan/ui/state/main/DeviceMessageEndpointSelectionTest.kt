package com.folderspan.ui.state.main

import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceMessageEndpointSelectionTest {
    @Test
    fun reconnectSelectsOnlyTheSameExplicitTransport() {
        val disconnected = endpoint(DeviceMessageTransport.Session, "old")
        val reconnected = endpoint(DeviceMessageTransport.Session, "new")

        val selected = resolveDeviceMessageEndpointSelection(
            selected = disconnected,
            preferred = PEER to DeviceMessageTransport.Session,
            liveEndpoints = setOf(reconnected),
        )

        assertEquals(reconnected, selected)
    }

    @Test
    fun ambiguousSameTransportDoesNotSilentlyChooseAnEndpoint() {
        val selected = resolveDeviceMessageEndpointSelection(
            selected = endpoint(DeviceMessageTransport.Session, "old"),
            preferred = PEER to DeviceMessageTransport.Session,
            liveEndpoints = setOf(
                endpoint(DeviceMessageTransport.Session, "new-a"),
                endpoint(DeviceMessageTransport.Session, "new-b"),
            ),
        )

        assertNull(selected)
    }

    private fun endpoint(transport: DeviceMessageTransport, connectionId: String) =
        DeviceMessageEndpointIdentity(
            peerDeviceId = PEER,
            transport = transport,
            connectionId = connectionId,
        )

    private companion object {
        const val PEER = "peer-device-message"
    }
}
