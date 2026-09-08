package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.test.runSuspendTest
import io.github.skeptick.libres.LibresSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class WebRtcRoomConnectionTesterTest {
    @BeforeTest
    fun useEnglish() {
        LibresSettings.languageCode = "en"
    }

    @Test
    fun blankWebSocketUrlFailsWithoutCreatingClient() = runSuspendTest {
        var factoryCalls = 0
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { _, _ ->
                factoryCalls += 1
                FakeProbeClient()
            }
        )

        val result = tester.testWebSocketConnection("   ")

        val failure = assertIs<WebRtcRoomConnectionTestResult.Failure>(result)
        assertEquals("WebSocket address cannot be empty.", failure.message)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun successfulSocketOpenReturnsSuccessWithoutSendingJoinMessages() = runSuspendTest {
        val client = FakeProbeClient(
            onConnect = { fake ->
                fake.emitOpened()
            }
        )
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { _, _ -> client }
        )

        val result = tester.testWebSocketConnection("wss://example.com/ws")

        assertEquals(WebRtcRoomConnectionTestResult.Success, result)
        assertTrue(client.sentMessages.isEmpty())
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun successfulSocketOpenReturnsBeforeLongLivedConnectionJobEnds() = runSuspendTest {
        val client = FakeProbeClient(
            onConnect = { fake ->
                fake.emitOpened()
                awaitCancellation()
            }
        )
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { _, _ -> client }
        )

        val result = withTimeout(200L.milliseconds) {
            tester.testWebSocketConnection("wss://example.com/ws")
        }

        assertEquals(WebRtcRoomConnectionTestResult.Success, result)
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun transportFailureReturnsFailureMessage() = runSuspendTest {
        val client = FakeProbeClient(
            onConnect = { fake ->
                fake.emitFailure(IllegalStateException("boom"))
            }
        )
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { _, _ -> client }
        )

        val result = tester.testWebSocketConnection("wss://example.com/ws")

        val failure = assertIs<WebRtcRoomConnectionTestResult.Failure>(result)
        assertEquals("boom", failure.message)
        assertTrue(client.sentMessages.isEmpty())
    }

    @Test
    fun timeoutReturnsFailure() = runSuspendTest {
        val client = FakeProbeClient()
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { _, _ -> client }
        )

        val result = tester.testWebSocketConnection(
            wssUrl = "wss://example.com/ws",
            timeoutMs = 20L
        )

        val failure = assertIs<WebRtcRoomConnectionTestResult.Failure>(result)
        assertEquals("The WebSocket connection test timed out. Check the address or network.", failure.message)
        assertNotEquals(0, client.connectCalls)
    }

    @Test
    fun testConnectionPassesHeadersToProbeClientFactory() = runSuspendTest {
        val client = FakeProbeClient(
            onConnect = { fake ->
                fake.emitOpened()
            }
        )
        var capturedUrl = ""
        var capturedHeaders = emptyMap<String, String>()
        val tester = WebRtcRoomConnectionTester(
            clientFactory = { url, headers ->
                capturedUrl = url
                capturedHeaders = headers
                client
            }
        )

        val result = tester.testWebSocketConnection(
            wssUrl = " wss://example.com/ws ",
            headers = mapOf("Authorization" to "Bearer token-value"),
        )

        assertEquals(WebRtcRoomConnectionTestResult.Success, result)
        assertEquals("wss://example.com/ws", capturedUrl)
        assertEquals(mapOf("Authorization" to "Bearer token-value"), capturedHeaders)
    }
}

private class FakeProbeClient(
    private val onConnect: suspend (FakeProbeClient) -> Unit = {}
) : WebRtcRoomConnectionProbeClient {
    private val messageFlow = MutableSharedFlow<SignalingMessage>(replay = 1, extraBufferCapacity = 1)
    private val openedFlow = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val closedFlow = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private val failureFlow = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)

    val sentMessages = mutableListOf<SignalingMessage>()
    var connectCalls = 0
        private set
    var closeCalls = 0
        private set

    override val messages: Flow<SignalingMessage> = messageFlow
    override val openedEvents: Flow<Unit> = openedFlow
    override val closedEvents: Flow<Unit> = closedFlow
    override val failureEvents: Flow<Throwable> = failureFlow

    override fun connect(scope: CoroutineScope) {
        connectCalls += 1
        scope.launch {
            onConnect(this@FakeProbeClient)
        }
    }

    override suspend fun send(message: SignalingMessage) {
        sentMessages += message
    }

    override suspend fun close() {
        closeCalls += 1
        closedFlow.emit(Unit)
    }

    suspend fun emitOpened() {
        openedFlow.emit(Unit)
    }

    suspend fun emitFailure(error: Throwable) {
        failureFlow.emit(error)
    }
}
