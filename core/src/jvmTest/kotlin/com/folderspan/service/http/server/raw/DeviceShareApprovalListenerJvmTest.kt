package com.folderspan.service.http.server.raw

import com.folderspan.service.http.client.createPinnedNoProxyHttpClient
import com.folderspan.service.http.server.defaultDeviceShareApprovalPort
import com.folderspan.service.http.tls.DeviceTlsIdentity
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.net.BindException
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceShareApprovalListenerJvmTest {
    @Test
    fun approvalPortIsBoundBeforeInternalPortsAreAllocated(): Unit = runBlocking {
        val sessionPort = allocateSessionPortPair()
        val approvalPort = defaultDeviceShareApprovalPort(sessionPort)
        var checkedApprovalPort = false
        val server = RawTlsHttpServer { reservedPorts ->
            assertFailsWith<BindException> {
                ServerSocket(approvalPort).use { }
            }
            checkedApprovalPort = true
            allocateInternalRawHttpServerPort(*reservedPorts)
        }
        try {
            server.start(sessionPort)
            assertTrue(checkedApprovalPort)
            assertTrue(server.isRunning())
        } finally {
            server.stop()
        }
    }

    @Test
    fun approvalHttpUsesDedicatedTlsPortWhileSessionPortRejectsHttp(): Unit = runBlocking {
        val server = RawTlsHttpServer()
        val client = createPinnedNoProxyHttpClient(DeviceTlsIdentity.loadOrCreate().fingerprintSha256) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 2_000
                connectTimeoutMillis = 2_000
                socketTimeoutMillis = 2_000
            }
        }
        try {
            val sessionPort = allocateSessionPortPair()
            val approvalPort = defaultDeviceShareApprovalPort(sessionPort)
            server.start(sessionPort)

            val approvalResponse = client.post("https://127.0.0.1:$approvalPort/not-heartbeat")
            assertEquals(HttpStatusCode.NotFound, approvalResponse.status)
            approvalResponse.body<ByteArray>()
            supervisorScope {
                val sessionHttpResult = runCatching {
                    client.post("https://127.0.0.1:$sessionPort/not-heartbeat")
                }
                assertTrue(sessionHttpResult.isFailure)
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun occupiedApprovalPortLeavesSessionPortReusableAndAllowsRestart(): Unit = runBlocking {
        val server = RawTlsHttpServer()
        val client = createPinnedNoProxyHttpClient(DeviceTlsIdentity.loadOrCreate().fingerprintSha256) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 2_000
                connectTimeoutMillis = 2_000
                socketTimeoutMillis = 2_000
            }
        }
        try {
            val sessionPort = allocateSessionPortPair()
            val approvalPort = defaultDeviceShareApprovalPort(sessionPort)
            ServerSocket(approvalPort).use {
                assertTrue(runCatching { server.start(sessionPort) }.isFailure)
                assertFalse(server.isRunning())
                ServerSocket(sessionPort).use { socket -> assertTrue(socket.isBound) }
            }

            server.start(sessionPort)
            assertTrue(server.isRunning())
            val response = client.post("https://127.0.0.1:$approvalPort/not-heartbeat")
            assertEquals(HttpStatusCode.NotFound, response.status)
            response.body<ByteArray>()
        } finally {
            client.close()
            server.stop()
        }
    }

    private fun allocateSessionPortPair(): Int {
        // 使用临时出站端口范围之外的测试端口，避免并行网络测试抢占派生审批端口。
        repeat(32) { index ->
            val sessionPort = 18_040 + index * 4
            val approvalPort = defaultDeviceShareApprovalPort(sessionPort)
            val availablePort = runCatching {
                ServerSocket(sessionPort).use {
                    ServerSocket(approvalPort).use { sessionPort }
                }
            }.getOrNull()
            if (availablePort != null) return availablePort
        }
        error("Unable to allocate device Session and approval test ports")
    }
}
