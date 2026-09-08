package com.folderspan.service.http.client

import com.folderspan.service.data.*
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

private const val DEVICE_HEARTBEAT_INITIAL_MAX_RETRIES = 20
private const val DEVICE_HEARTBEAT_INITIAL_STALE_DISCONNECT_MS = 120_000L
private const val DEVICE_HEARTBEAT_INTERVAL_MS = 3_000L
private const val DEVICE_HEARTBEAT_TIMEOUT_MS = 30_000L
private const val DEVICE_HEARTBEAT_CONNECTED_FAILURE_LIMIT = 5
internal const val DEVICE_HEARTBEAT_STALE_DISCONNECT_MS = 120_000L

class DeviceRouteClient(
    private val httpClient: HttpClient,
    private val manager: HttpRouteClientManager
) {

    suspend fun connectDevice(
        request: DeviceConnectRequest,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<DeviceConnectResponse> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_start_device_connection_id_arg0_name_arg1.format(arg0 = (request.device.id), arg1 = (request.device.name)))
            val response = httpClient.post("/api/devices/connect") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_device_connection_request_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            // 如果服务器没有设置正确的 Content-Type，手动解析
            val responseBody = decodeHttpSuccessBody<DeviceConnectResponse>(response.folderSpanBodyBytes())
            LogKit.d(AppStrings.ui_device_connection_result_arg0_tokenlen_arg1.format(arg0 = (responseBody.connectType).toString(), arg1 = (responseBody.token.length).toString()))
            Result.success(responseBody)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_device_connection_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    suspend fun getDeviceTheme(
        isDark: Boolean,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<DeviceThemeResponse> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_device_theme_isdark_arg0.format(arg0 = (isDark).toString()))
            val request = DeviceThemeRequest(isDark = isDark)
            val response = httpClient.post("/api/devices/theme") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_get_device_theme_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val responseBody = decodeHttpSuccessBody<DeviceThemeResponse>(response.folderSpanBodyBytes())
            Result.success(responseBody)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_get_device_theme_anomaly_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    suspend fun heartbeat() {
        val start = Clock.System.now().toEpochMilliseconds()
        var connectedOnce = false
        var backoffMs = 1000L
        var lastSuccessTime = start
        var retryCount = 0
        var consecutiveFailures = 0

        while (true) {
            try {
                retryCount++
                val request = DeviceHeartbeatRequest(
                    clientTimeMillis = Clock.System.now().toEpochMilliseconds()
                )
                val response = httpClient.post("/api/devices/heartbeat") {
                    timeout {
                        requestTimeoutMillis = DEVICE_HEARTBEAT_TIMEOUT_MS
                        connectTimeoutMillis = DEVICE_HEARTBEAT_TIMEOUT_MS
                        socketTimeoutMillis = DEVICE_HEARTBEAT_TIMEOUT_MS
                    }
                    setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
                }

                if (!response.status.isSuccess()) {
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_heartbeat_request_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                decodeHttpSuccessBody<DeviceHeartbeatResponse>(response.folderSpanBodyBytes())
                connectedOnce = true
                consecutiveFailures = 0  // 心跳成功，重置连续失败计数
                backoffMs = 1_000L
                lastSuccessTime = Clock.System.now().toEpochMilliseconds()
                delay(DEVICE_HEARTBEAT_INTERVAL_MS.milliseconds)
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                if (e is CancellationException) throw e

                consecutiveFailures++
                val now = Clock.System.now().toEpochMilliseconds()

                // 检查最大重试次数
                if (!connectedOnce && retryCount >= DEVICE_HEARTBEAT_INITIAL_MAX_RETRIES) {
                    runCatching { manager.disconnect(notifyCancel = false) }
                    LogKit.e(AppStrings.ui_heartbeat_first_retry_arg0_after_failure_stop_arg1.format(arg0 = (retryCount).toString(), arg1 = (e.message).toString()))
                    throw Exception(AppStrings.ui_heartbeat_first_retry_arg0_after_failure.format(arg0 = (retryCount).toString()))
                }

                if (!connectedOnce && now - start >= DEVICE_HEARTBEAT_INITIAL_STALE_DISCONNECT_MS) {
                    runCatching { manager.disconnect(notifyCancel = false) }
                    LogKit.e(
                        AppStrings.ui_heartbeat_first_connection_exceeds_arg0_seconds_and_fails_stopping_arg1.format(arg0 = (DEVICE_HEARTBEAT_INITIAL_STALE_DISCONNECT_MS / 1000).toString(), arg1 = (e.message).toString())
                    )
                    throw e
                }

                // 检查连续失败次数
                if (connectedOnce && consecutiveFailures >= DEVICE_HEARTBEAT_CONNECTED_FAILURE_LIMIT) {
                    runCatching { manager.disconnect(notifyCancel = false) }
                    LogKit.e(AppStrings.ui_heartbeat_failed_consecutively_arg0_times_stopping_arg1.format(arg0 = (consecutiveFailures).toString(), arg1 = (e.message).toString()))
                    throw Exception(AppStrings.ui_heartbeat_failed_arg0_times.format(arg0 = (consecutiveFailures).toString()))
                }

                if (now - lastSuccessTime >= DEVICE_HEARTBEAT_STALE_DISCONNECT_MS) {
                    runCatching { manager.disconnect(notifyCancel = false) }
                    LogKit.e(AppStrings.ui_heartbeat_failed_to_connect_multiple_times_within_arg0_seconds_stopping_arg1.format(arg0 = (DEVICE_HEARTBEAT_STALE_DISCONNECT_MS / 1000).toString(), arg1 = (e.message).toString()))
                    throw Exception(AppStrings.ui_heartbeat_failed_to_connect_multiple_times_within_arg0_seconds.format(arg0 = (DEVICE_HEARTBEAT_STALE_DISCONNECT_MS / 1000).toString()))
                }

                LogKit.d(
                    AppStrings.ui_heartbeat_retry_attempt_arg0_arg1_consecutive_failures.format(arg0 = (retryCount).toString(), arg1 = (consecutiveFailures).toString()) +
                        AppStrings.ui_backing_off_for_arg0_ms_reason_arg1.format(arg0 = (backoffMs).toString(), arg1 = (e.message.orEmpty()))
                )
                delay(backoffMs.milliseconds)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }
}
