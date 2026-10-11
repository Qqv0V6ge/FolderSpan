package com.folderspan.service.http.client

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.getSocketDevice
import com.folderspan.service.account.ACCOUNT_DEVICE_CONNECT_PURPOSE
import com.folderspan.service.account.AccountDeviceProofContext
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.account.accountDeviceSha256
import com.folderspan.service.account.createAccountDeviceProof
import com.folderspan.service.account.newAccountDeviceNonce
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceConnectAuthorizationMode
import com.folderspan.service.data.DeviceConnectRequest
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.plugins.CloseOnUnauthorized
import com.folderspan.service.http.tls.DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.createDeviceIdentityProof
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.utils.ProtoBufCodec
import com.folderspan.utils.SettingsUtils
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.protobuf.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import strings.AppStrings

internal enum class HttpRouteDisconnectReason {
    Unauthorized,
    Explicit,
}

internal fun DeviceConnectType.toHttpConnectType(): ConnectType = when (this) {
    DeviceConnectType.APPROVED -> ConnectType.Connect
    DeviceConnectType.REJECTED,
    DeviceConnectType.PERMANENTLY_BANNED -> ConnectType.Rejected
    DeviceConnectType.AUTO_CONNECT,
    DeviceConnectType.WAITING -> ConnectType.Fail
}

class HttpRouteClientManager : KoinComponent {
    private val deviceState by inject<DeviceState>()
    private val accountDeviceTrustRegistry by inject<AccountDeviceTrustRegistry>()
    private val accountDeviceTrustRefreshTrigger by inject<AccountDeviceTrustRefreshTrigger>()

    internal val requestRegistry = HttpClientRequestRegistry()

    lateinit var deviceRouteClient: DeviceRouteClient
    lateinit var bookmarkRouteClient: BookmarkRouteClient
    lateinit var fileRouteClient: FileRouteClient
    lateinit var pathRouteClient: PathRouteClient
    internal var encryptedHttpTransport: Boolean = false
        private set

    /**
     * 创建配置好的 HttpClient 实例
     *
     * @param baseUrl 服务器基础 URL
     * @param token 可选的认证令牌，用于 Bearer 鉴权
     * @param onCancelConnect 当收到 401 未授权响应时的回调函数
     * @return 配置好的 HttpClient 实例，包含 Protobuf 序列化和鉴权拦截器
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClient(
        baseUrl: String,
        tlsFingerprintSha256: String,
        token: String = "",
        onCancelConnect: (suspend () -> Unit)? = null,
    ): HttpClient {
        return createPinnedNoProxyHttpClient(tlsFingerprintSha256) {
            install(ContentNegotiation) {
                protobuf()
            }

            install(HttpTimeout)

            // 当服务端返回 401 时，立刻终止本次请求，并关闭 HttpRouteClientManager
            install(CloseOnUnauthorized) {
                onUnauthorized = onCancelConnect
            }

            defaultRequest {
                url(baseUrl)
                contentType(ContentType.Application.ProtoBuf)
                accept(ContentType.Application.ProtoBuf)
                headers.applyFileShareAccessKey()
                if (token.isNotEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
        }
    }

    private lateinit var sharedHttpClient: HttpClient
    private lateinit var deviceControlHttpClient: HttpClient

    // 外部取消连接/鉴权失效回调
    private var cancelConnectCallback: (suspend (HttpRouteDisconnectReason) -> Unit)? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 设置取消连接时的外部回调函数
     *
     * @param callback 取消连接时执行的回调，可用于 UI 通知或业务逻辑清理
     * @return 当前 HttpRouteClientManager 实例，支持链式调用
     */
    internal fun onCancelConnect(
        callback: suspend (HttpRouteDisconnectReason) -> Unit
    ): HttpRouteClientManager {
        cancelConnectCallback = callback
        return this
    }

    /**
     * 处理取消连接的内部逻辑
     *
     * 先调用外部设置的回调函数，然后执行默认清理操作：
     * - 从已连接设备列表中移除该设备
     * - 更新设备连接状态为未连接
     * - 清空认证令牌和 httpClient 引用
     * - 关闭共享的 HttpClient 实例
     */
    private suspend fun handleCancelConnect() {
        try {
            cancelConnectCallback?.invoke(HttpRouteDisconnectReason.Unauthorized)
        } catch (_: Exception) {
            // 忽略外部回调抛出的异常，继续执行默认清理
        } finally {
            cleanupConnectedDevice(ConnectType.UnConnect)
            closeSharedHttpClient()
            cleanupScope.cancel()
        }
    }

    private suspend fun cleanupConnectedDevice(connectType: ConnectType) {
        withContext(Dispatchers.Main) {
            // 默认清理：更新设备连接状态并关闭客户端
            val index = deviceState.socketDevices.indexOfFirst { item ->
                item.httpClient == this@HttpRouteClientManager
            }
            if (index >= 0) {
                val socketDevice = deviceState.socketDevices[index]
                // 从已连接列表移除
                deviceState.devices.removeAll { item -> item.id == socketDevice.id }
                // 更新连接状态并清空 token 与 httpClient 引用
                deviceState.socketDevices[index] = socketDevice.withCopy(
                    connectType = connectType,
                    token = "",
                    httpClient = null,
                )
            }
        }
    }

    private fun closeSharedHttpClient() {
        if (::sharedHttpClient.isInitialized) {
            runCatching { sharedHttpClient.close() }
        }
        if (::deviceControlHttpClient.isInitialized) {
            runCatching { deviceControlHttpClient.close() }
        }
    }

    /**
     * 连接到指定设备
     *
     * 执行流程：
     * 1. 创建临时客户端获取认证令牌
     * 2. 使用令牌创建带鉴权的 HttpClient
     * 3. 更新设备连接状态
     * 4. 初始化各路由客户端（设备、书签、文件、路径）
     *
     * @param connectDevice 要连接的设备信息
     * @throws Exception 当连接失败或认证失败时抛出异常
     */
    suspend fun connect(
        connectDevice: SocketDevice,
        allowChangedTrustedCertificate: Boolean = false,
        accountDeviceAuthorization: Boolean = false,
    ) {
        val tlsFingerprint = connectDevice.normalizedTlsFingerprint()
        if (tlsFingerprint.isBlank()) {
            throw IllegalStateException(AppStrings.ui_the_device_lacks_tls_certificate_fingerprint_preventing_https_connection)
        }
        if (shouldBlockChangedTrustedDeviceCertificate(
                deviceId = connectDevice.id,
                tlsFingerprint = tlsFingerprint,
                allowChangedTrustedCertificate = allowChangedTrustedCertificate,
            )
        ) {
            throw IllegalStateException(DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)
        }
        encryptedHttpTransport = usePlainHttpDeviceTransport()
        val baseUrl = connectDevice.deviceApiBaseUrl()

        // 先获取认证令牌
        val tempClient = createHttpClient(baseUrl, tlsFingerprint)
        val tempDeviceClient = DeviceRouteClient(tempClient, this)
        val selfDevice = getSocketDevice()
        val featureEnabled = SettingsUtils.fileShare.isAccountDeviceAutoConnectEnabled()
        if (accountDeviceAuthorization && featureEnabled &&
            accountDeviceTrustRegistry.state.value.isNearExpiry(Clock.System.now().toEpochMilliseconds())
        ) {
            accountDeviceTrustRefreshTrigger.refreshIfNeeded(force = false)
        }
        val trustSnapshot = accountDeviceTrustRegistry.state.value
        val nowEpochMillis = Clock.System.now().toEpochMilliseconds()
        val authorizationMode = if (accountDeviceAuthorization) {
            DeviceConnectAuthorizationMode.ACCOUNT_DEVICE
        } else {
            DeviceConnectAuthorizationMode.STANDARD
        }
        val unsignedRequest = DeviceConnectRequest(
            device = selfDevice,
            authorizationMode = authorizationMode,
        )
        val unsignedPayloadSha256 = ProtoBufCodec.encode(unsignedRequest).accountDeviceSha256()
        val identityProof = createDeviceIdentityProof(
            targetDeviceId = connectDevice.id,
            targetPath = "/api/devices/connect",
            payloadSha256 = unsignedPayloadSha256,
            nowEpochSeconds = nowEpochMillis / 1_000L,
            deviceId = selfDevice.id,
        )
        val accountProof = if (accountDeviceAuthorization) {
            check(featureEnabled) { AppStrings.ui_account_device_auto_connect_is_disabled }
            check(trustSnapshot.isFresh(nowEpochMillis)) { AppStrings.ui_account_device_trust_list_expired }
            check(trustSnapshot.devices.containsKey(connectDevice.id)) { AppStrings.ui_the_device_is_not_in_the_current_accounts_trusted_list }
            val nonce = newAccountDeviceNonce()
            checkNotNull(createAccountDeviceProof(
                AccountDeviceProofContext(
                    purpose = ACCOUNT_DEVICE_CONNECT_PURPOSE,
                    signerDeviceKey = selfDevice.id,
                    targetDeviceKey = connectDevice.id,
                    targetPath = "/api/devices/connect",
                    nonce = nonce,
                    issuedAtEpochSeconds = nowEpochMillis / 1_000L,
                    payloadSha256 = unsignedPayloadSha256,
                )
            )) { AppStrings.ui_no_account_device_connection_proof_can_be_generated }
        } else {
            null
        }
        val result = try {
            tempDeviceClient.connectDevice(
                unsignedRequest.copy(
                    accountDeviceProof = accountProof,
                    identityProof = identityProof,
                )
            )
        } finally {
            tempClient.close()
        }

        if (result.isFailure) {
            throw result.exceptionOrNull()!!
        }

        val connectResponse = result.getOrThrow()
        check(connectResponse.authorizationMode != DeviceConnectAuthorizationMode.UNSPECIFIED) {
            AppStrings.ui_device_connection_response_missing_authorization_mode
        }
        val resolvedConnectType = connectResponse.connectType.toHttpConnectType()
        if (connectResponse.connectType == DeviceConnectType.APPROVED) {
            TrustedDeviceCertificateStore.save(connectDevice.id, tlsFingerprint)
            if (connectResponse.authorizationMode == DeviceConnectAuthorizationMode.ACCOUNT_DEVICE) {
                deviceState.markAccountDeviceAutoAuthorized(connectDevice.id)
            }
        } else {
            withContext(Dispatchers.Main) {
                val rejectedDevice = connectDevice.withCopy(
                    connectType = resolvedConnectType,
                    token = "",
                    httpClient = null,
                )
                val index = deviceState.socketDevices.indexOfFirst { item -> item.id == connectDevice.id }
                if (index >= 0) {
                    deviceState.socketDevices[index] = rejectedDevice
                } else {
                    deviceState.socketDevices.add(rejectedDevice)
                }
            }
            return
        }

        // 文件/路径请求和设备控制请求分开连接池，避免大批量目录遍历挤压心跳与鉴权链路。
        sharedHttpClient = createHttpClient(baseUrl, tlsFingerprint, connectResponse.token) {
            handleCancelConnect()
        }
        deviceControlHttpClient = createHttpClient(baseUrl, tlsFingerprint, connectResponse.token) {
            handleCancelConnect()
        }

        // 初始化使用带鉴权 HttpClient 的路由客户端
        deviceRouteClient = DeviceRouteClient(deviceControlHttpClient, this)
        bookmarkRouteClient = BookmarkRouteClient(sharedHttpClient, this)
        fileRouteClient = FileRouteClient(sharedHttpClient, this)
        pathRouteClient = PathRouteClient(sharedHttpClient, this)

        val socketDevice = connectDevice.withCopy(
            connectType = resolvedConnectType,
            token = connectResponse.token,
            httpClient = this,
        )
        withContext(Dispatchers.Main) {
            val index = deviceState.socketDevices.indexOfFirst { item -> item.id == connectDevice.id }
            if (index >= 0) {
                deviceState.socketDevices[index] = socketDevice
            } else {
                deviceState.socketDevices.add(socketDevice)
            }
            if (socketDevice.connectType == ConnectType.Connect) {
                deviceState.devices.add(socketDevice.toDevice())
            }
        }
    }

    /**
     * 断开当前连接
     *
     * @return 始终返回 true 表示断开操作已执行
     */
    fun disconnect(notifyCancel: Boolean = true): Boolean {
        if (notifyCancel) {
            // 通知外部取消（用于清理 UI 状态、停止心跳等）
            cleanupScope.launch {
                try {
                    cancelConnectCallback?.invoke(HttpRouteDisconnectReason.Explicit)
                } catch (_: Exception) {
                    // 忽略外部回调抛出的异常，继续执行默认清理
                } finally {
                    cleanupConnectedDevice(ConnectType.UnConnect)
                    cleanupScope.cancel()
                }
            }
        } else {
            cleanupScope.cancel()
        }
        // 关闭共享的 HttpClient
        closeSharedHttpClient()
        return true
    }

    /**
     * 取消指定的请求
     *
     * @param requestId 要取消的请求 ID
     * @param reason 取消原因，默认为"取消请求"
     * @return 是否成功取消该请求
     */
    suspend fun cancelRequest(requestId: String, reason: String = AppStrings.ui_cancel_request): Boolean {
        return requestRegistry.cancelRequest(requestId, reason)
    }

    /**
     * 取消指定批次的所有请求
     *
     * @param batchId 批次 ID
     * @param reason 取消原因，默认为"取消请求批次"
     * @return 成功取消的请求数量
     */
    suspend fun cancelBatch(batchId: String, reason: String = AppStrings.ui_cancel_request_batch): Int {
        return requestRegistry.cancelBatch(batchId, reason)
    }

    companion object {
        // 10 minutes in seconds
        const val CONNECT_TIMEOUT = 600

        const val PORT = 12040

        /**
         * 表示通用 HTTP/Share/RPC 分片长度的常量，用于定义在数据传输过程中每个分片的默认最大长度。
         * 此值用于确保分片在网络传输时的稳定性和效率，避免超出可接受的大小限制。
         * 常量默认为 8 * 1024 * 1024，即 8MB。
         */
        const val MAX_LENGTH = 8 * 1024 * 1024 // 8MB 最大分片长度

        /**
         * 设备直连 HTTP byte-route 的最大分片长度。
         *
         * 设备直连复制使用显式 startOffset 写入，因此可以在不改变协议字段的情况下把
         * range 请求从 8MB 提高到 16MB，减少高吞吐局域网中的请求往返和调度开销。
         */
        const val DEVICE_DIRECT_MAX_LENGTH = 16 * 1024 * 1024

        /**
         * 设备直连下载长流 range 的最大长度。
         *
         * 该端点不把整个 range 聚合成 ByteArray，本地和远端都用固定缓冲流式读写，
         * 因此可以用更长的 HTTP 响应降低请求轮次、protobuf 编解码和 TLS 调度开销。
         */
        const val DEVICE_DIRECT_STREAM_RANGE_BYTES = 64 * 1024 * 1024

        /**
         * 设备直连下载长流的客户端目标 range 长度。
         *
         * 服务端仍允许 64MB 上限；客户端默认用 32MB 保留长流低请求数优势，
         * 同时让 12 路连接更快滚动，避免 6 路 64MB 长连接填不满链路。
         */
        const val DEVICE_DIRECT_STREAM_TARGET_RANGE_BYTES = 32 * 1024 * 1024

        /**
         * HTTP 分片传输的最大并发块数量。
         */
        const val MAX_CONCURRENT_FILE_CHUNKS = 64

        /**
         * 设备直连 HTTP 分片传输的最大 in-flight byte 预算。
         */
        const val DEVICE_DIRECT_MAX_IN_FLIGHT_BYTES = 128 * 1024 * 1024

        /**
         * 设备直连 HTTP 客户端本地默认并发上限。保留 128MB 预算，但避免直接把
         * 8MB range 拉到 16 路导致 TLS、磁盘或 GC 反压。
         */
        const val DEVICE_DIRECT_MAX_PARALLEL_REQUESTS = 12

        /**
         * 设备直连长流下载的默认并发上限。长流减少请求轮次，但仍需要保留
         * byte-route 同级连接宽度，否则高速局域网会因为单连接吞吐不足变慢。
         */
        const val DEVICE_DIRECT_STREAM_PARALLEL_REQUESTS = 12

        /**
         * 设备直连长流下载的服务端响应块和客户端本地写缓冲。
         */
        const val DEVICE_DIRECT_STREAM_BUFFER_BYTES = 4 * 1024 * 1024

        /**
         * 设备直连读写解耦管线的预取队列深度。
         */
        const val DEVICE_DIRECT_PREFETCH_QUEUE_DEPTH = 4

        /**
         * 设备与 Share raw byte 响应的客户端读取缓冲。
         */
        const val RAW_BYTE_READ_BUFFER_BYTES = 2 * 1024 * 1024

        /**
         * 设备间普通 HTTP 分片管线深度上限。
         */
        const val DEVICE_TRANSPORT_PIPELINE_DEPTH = 64
    }
}

internal fun shouldBlockChangedTrustedDeviceCertificate(
    deviceId: String,
    tlsFingerprint: String,
    allowChangedTrustedCertificate: Boolean,
): Boolean {
    return !allowChangedTrustedCertificate &&
        TrustedDeviceCertificateStore.has(deviceId) &&
        !TrustedDeviceCertificateStore.verify(deviceId, tlsFingerprint)
}
