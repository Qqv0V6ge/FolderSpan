package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.test.runSuspendTest
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceSessionIdentityBootstrapTest {
    @Test
    fun identifyUsesHandshakeFingerprintAndReturnsRoutableDevice() = runSuspendTest {
        coroutineScope {
            val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
            val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)
            val server = testPeer(
                channel = IdentityTestByteChannel(clientToServer, serverToClient),
                role = DeviceSessionEndpointRole.Server,
            )
            server.setRequestHandler { request ->
                assertEquals(DEVICE_SESSION_RPC_IDENTIFY, request.method)
                DeviceSessionControlResponse(
                    requestId = request.requestId,
                    status = DEVICE_SESSION_RPC_OK,
                    payload = ProtoBufCodec.encode(
                        SocketDevice(
                            id = "remote-device",
                            name = "Remote",
                            pathSeparator = "/",
                            port = 12042,
                            httpsPort = 12040,
                            type = DeviceType.JVM,
                            connectType = ConnectType.UnConnect,
                            tlsFingerprintSha256 = FINGERPRINT,
                        )
                    ),
                )
            }
            server.start(this)

            try {
                val identified = identifyDeviceSessionEndpoint("10.0.0.226", 12040) { _, _ ->
                    DeviceSessionBootstrapConnection(
                        channel = IdentityTestByteChannel(serverToClient, clientToServer),
                        peerFingerprintSha256 = FINGERPRINT,
                    )
                }

                assertEquals("remote-device", identified.id)
                assertEquals("10.0.0.226", identified.host)
                assertEquals(12042, identified.port)
                assertEquals(12040, identified.httpsPort)
                assertEquals(FINGERPRINT, identified.tlsFingerprintSha256)
            } finally {
                server.close()
            }
        }
    }

    private fun testPeer(
        channel: DeviceSessionByteChannel,
        role: DeviceSessionEndpointRole,
    ): DeviceSessionPeer {
        val plan = DeviceSessionWindowPlan(
            sessionWindowBytes = 2 * 1024 * 1024,
            streamWindowBytes = DEVICE_SESSION_MAX_PAYLOAD_BYTES,
            maxFileStreams = 8,
        )
        return DeviceSessionPeer(
            transport = DeviceSessionTransport(channel, plan, isClient = role == DeviceSessionEndpointRole.Client),
            receivePlan = plan,
            role = role,
        )
    }

    private class IdentityTestByteChannel(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
    ) : DeviceSessionByteChannel {
        private var current = ByteArray(0)
        private var currentOffset = 0

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (currentOffset >= current.size) {
                current = incoming.receiveCatching().getOrNull() ?: return -1
                currentOffset = 0
            }
            val count = minOf(length, current.size - currentOffset)
            current.copyInto(
                destination = buffer,
                destinationOffset = offset,
                startIndex = currentOffset,
                endIndex = currentOffset + count,
            )
            currentOffset += count
            return count
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            outgoing.send(buffer.copyOfRange(offset, offset + length))
        }

        override suspend fun flush() = Unit

        override fun close() {
            outgoing.close()
        }
    }

    private companion object {
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    }
}
