package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.session.DeviceSessionClientManager
import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SettingsUtils
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocketDevicePresenceTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun probeAliveSkipsUnpinnedHttpsWithoutSendingAccessKey() {
        runBlocking {
            SettingsUtils.init(createInMemorySettings())
            var captured = null as HttpRequestData?
            val remoteDevice = SocketDevice(
                id = "presence-remote",
                name = "Remote",
                pathSeparator = "/",
                host = "203.0.113.10",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
            )
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel(ProtoBuf.encodeToByteArray(SocketDevice.serializer(), remoteDevice)),
                    status = HttpStatusCode.OK,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/protobuf")
                    },
                )
            }
            val client = HttpClient(engine)

            client.use { client ->
                val alive = remoteDevice.probeAlive(client)
                assertNull(captured, "probeAlive must not send HTTPS heartbeats without a pinned fingerprint")
                assertFalse(alive)
            }
        }
    }

    @Test
    fun probeAliveTreatsConnectedSessionAsAliveWithoutHttpsPing() {
        runBlocking {
            SettingsUtils.init(createInMemorySettings())
            var captured = null as HttpRequestData?
            val remoteDevice = SocketDevice(
                id = "presence-session",
                name = "Remote",
                pathSeparator = "/",
                host = "203.0.113.10",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                tlsFingerprintSha256 = "a".repeat(64),
            ).apply {
                sessionClient = DeviceSessionClientManager()
            }
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                )
            }
            val client = HttpClient(engine)

            client.use { client ->
                val alive = remoteDevice.probeAlive(client)
                assertNull(captured, "connected sessions must not be probed with HTTPS /ping")
                assertTrue(alive)
            }
        }
    }

    @Test
    fun probeAliveDoesNotSendHttpsPingForSessionOnlyDevices() {
        runBlocking {
            SettingsUtils.init(createInMemorySettings())
            var captured = null as HttpRequestData?
            val remoteDevice = SocketDevice(
                id = "presence-session-only",
                name = "Remote",
                pathSeparator = "/",
                host = "203.0.113.10",
                port = 12040,
                httpsPort = 12040,
                type = DeviceType.JVM,
                tlsFingerprintSha256 = "a".repeat(64),
            )
            val engine = MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                )
            }
            val client = HttpClient(engine)

            client.use { client ->
                val alive = remoteDevice.probeAlive(client)
                assertNull(captured, "session-only devices must not fall back to HTTPS /ping")
                assertFalse(alive)
            }
        }
    }
}
