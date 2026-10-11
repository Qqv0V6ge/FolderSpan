package com.folderspan.service.webrtc

import com.folderspan.extensions.isConnectionException
import com.folderspan.service.webrtc.signaling.SignalingMessage
import com.folderspan.service.webrtc.signaling.WebRtcSignalingClient
import io.ktor.util.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import strings.AppStrings
import kotlin.time.Duration.Companion.milliseconds

const val DEFAULT_WEBRTC_ROOM_CONNECTION_TEST_TIMEOUT_MS = 8_000L

sealed interface WebRtcRoomConnectionTestResult {
    data object Success : WebRtcRoomConnectionTestResult

    data class Failure(
        val message: String,
        val code: String? = null
    ) : WebRtcRoomConnectionTestResult
}

interface WebRtcRoomConnectionProbeClient {
    val messages: Flow<SignalingMessage>
    val openedEvents: Flow<Unit>
    val closedEvents: Flow<Unit>
    val failureEvents: Flow<Throwable>

    fun connect(scope: CoroutineScope)

    suspend fun send(message: SignalingMessage)

    suspend fun close()
}

fun interface WebRtcRoomConnectionProbeClientFactory {
    fun create(wssUrl: String, headers: Map<String, String>): WebRtcRoomConnectionProbeClient
}

class WebRtcRoomConnectionTester(
    private val clientFactory: WebRtcRoomConnectionProbeClientFactory = WebRtcRoomConnectionProbeClientFactory { wssUrl, headers ->
        WebRtcSignalingClient(wssUrl, headers)
    },
    private val defaultTimeoutMs: Long = DEFAULT_WEBRTC_ROOM_CONNECTION_TEST_TIMEOUT_MS
) {
    suspend fun testWebSocketConnection(
        wssUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = defaultTimeoutMs
    ): WebRtcRoomConnectionTestResult {
        val normalizedUrl = wssUrl.trim()
        val validationError = validate(normalizedUrl)
        if (validationError != null) {
            return validationError
        }

        return withContext(Dispatchers.Default) {
            val client = clientFactory.create(normalizedUrl, headers)
            val probeJob = SupervisorJob(coroutineContext[Job])
            val probeScope = CoroutineScope(coroutineContext + probeJob)
            try {
                client.connect(probeScope)

                withTimeout(timeoutMs.milliseconds) {
                    merge(
                        client.openedEvents.map {
                            WebRtcRoomConnectionTestResult.Success
                        },
                        client.failureEvents.map { error ->
                            WebRtcRoomConnectionTestResult.Failure(
                                message = normalizeTransportErrorMessage(error)
                            )
                        },
                        client.closedEvents.map {
                            WebRtcRoomConnectionTestResult.Failure(
                                message = AppStrings.webrtc_test_connection_closed,
                            )
                        }
                    ).first()
                }
            } catch (_: TimeoutCancellationException) {
                WebRtcRoomConnectionTestResult.Failure(
                    message = AppStrings.webrtc_test_connection_timeout,
                )
            } catch (error: Throwable) {
                WebRtcRoomConnectionTestResult.Failure(
                    message = normalizeTransportErrorMessage(error)
                )
            } finally {
                runCatching { client.close() }
                probeJob.cancelAndJoin()
            }
        }
    }

    private fun validate(wssUrl: String): WebRtcRoomConnectionTestResult.Failure? {
        return if (wssUrl.isBlank()) {
            WebRtcRoomConnectionTestResult.Failure(AppStrings.ui_websocket_address_cannot_empty)
        } else {
            null
        }
    }
}

private fun normalizeTransportErrorMessage(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return when {
        error is UnresolvedAddressException -> AppStrings.webrtc_test_address_unresolved
        error.isConnectionException() -> AppStrings.webrtc_test_connection_failed
        message.contains("tls", ignoreCase = true) || message.contains("ssl", ignoreCase = true) -> {
            AppStrings.webrtc_test_tls_failed
        }

        message.isNotBlank() -> message
        else -> AppStrings.webrtc_test_failed
    }
}
