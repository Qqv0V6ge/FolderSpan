package com.folderspan.service.http.client

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.service.data.*
import com.folderspan.service.operation.*
import com.folderspan.service.path.DevicePathClient
import com.folderspan.service.path.normalizeDeviceTraversePath
import com.folderspan.ui.state.main.*
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings
import kotlin.time.Duration.Companion.milliseconds

private const val PATH_LIST_MAX_ATTEMPTS = 2
private const val PATH_LIST_RETRY_DELAY_MS = 250L

class PathRouteClient(
    private val httpClient: HttpClient,
    private val manager: HttpRouteClientManager
) : DevicePathClient, KoinComponent {
    private val deviceState: DeviceState by inject()
    private val taskState: TaskState by inject()
    private val transferStatusCache = HttpTransferStatusCache()
    private val transferClient by lazy {
        PathRouteTransferClient(
            manager = manager,
            deviceState = deviceState,
            taskState = taskState,
            transferStatusProvider = { transferStatus() },
            listPathBlock = { request, batchId ->
                listPath(request = request, batchId = batchId)
            },
            ensureCopyActiveBlock = { task, src, dest ->
                ensureCopyActive(task, src, dest)
            },
        )
    }


    override fun transferStatus(): HttpTransferStatus = transferStatusCache.current()

    /**
     * 消费 Device Server 在路径列表响应里返回的负载状态，让遍历并发跟随远端真实压力变化。
     */
    private fun updateTransferStatus(response: HttpResponse) {
        HttpTransferStatusHeaders.parse { name -> response.headers[name] }?.let { status ->
            transferStatusCache.update(status)
        }
    }

    private fun findConnectedDevice(deviceId: String) =
        deviceState.socketDevices.firstOrNull { device ->
            device.id == deviceId && (device.sessionClient != null || device.httpClient != null)
        }

    private fun ensureDeviceConnected(deviceId: String, errorMessage: String) {
        if (deviceId.isBlank()) return
        if (findConnectedDevice(deviceId) == null) {
            throw DeviceEndpointUnavailableException(errorMessage)
        }
    }

    private suspend fun ensureCopyActive(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo? = null,
        destFileSimpleInfo: FileSimpleInfo? = null,
    ) {
        if (!taskState.awaitIfPaused(task.key)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
        if (taskState.isTaskCancelled(task.key)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
        if (srcFileSimpleInfo?.protocol == FileProtocol.Device) {
            ensureDeviceConnected(
                srcFileSimpleInfo.protocolId,
                AppStrings.message_task_source_device_disconnected,
            )
        }
        if (destFileSimpleInfo?.protocol == FileProtocol.Device) {
            ensureDeviceConnected(
                destFileSimpleInfo.protocolId,
                AppStrings.message_task_target_device_disconnected,
            )
        }
    }

    suspend fun getRootPaths(): Result<List<PathInfo>> {
        return getRootPaths(requestId = null, batchId = null)
    }

    override suspend fun getRootPaths(
        requestId: String?,
        batchId: String?,
    ): Result<List<PathInfo>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_get_root_path_client)
            val response = httpClient.post("/api/paths/rootPaths") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(EmptyRequest()), manager.encryptedHttpTransport)
            }
            updateTransferStatus(response)

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_get_root_path_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val list = decodeHttpSuccessBody<List<PathInfo>>(bytes)
            LogKit.d(AppStrings.ui_get_root_path_succeeded_count_arg0.format(arg0 = (list.size).toString()))
            Result.success(list)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_root_path_exception_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    suspend fun listPath(
        request: ListRequest,
    ): Result<List<FileSimpleInfo>> {
        return listPath(request = request, requestId = null, batchId = null)
    }

    override suspend fun listPath(
        request: ListRequest,
        requestId: String?,
        batchId: String?,
    ): Result<List<FileSimpleInfo>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            var attempt = 1
            var finalResult: Result<List<FileSimpleInfo>>? = null
            while (finalResult == null) {
                try {
                    val fileSimpleInfos: MutableList<FileSimpleInfo> = mutableListOf()

                    val response = httpClient.post("/api/paths/list") {
                        setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
                    }
                    updateTransferStatus(response)

                    if (!response.status.isSuccess()) {
                        val error = readHttpResponseException(response)
                        LogKit.w(AppStrings.ui_list_of_paths_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                        throw error
                    }

                    val responseBody =
                        decodeHttpResultBody<Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>>>(
                            response.folderSpanBodyBytes()
                        )
                    if (responseBody.isFailure) {
                        throw responseBody.exceptionOrNull()!!
                    }
                    responseBody.getOrDefault(mapOf()).forEach { (protocol, fileInfos) ->
                        for (info in fileInfos) {
                            fileSimpleInfos.add(info.apply {
                                this.protocol = protocol.first
                                this.protocolId = protocol.second
                                this.path = request.path + this.path
                            })
                        }
                    }

                    finalResult = Result.success(fileSimpleInfos)
                } catch (e: CancellationException) {
                    finalResult = Result.failure(e)
                } catch (e: Exception) {
                    if (attempt < PATH_LIST_MAX_ATTEMPTS && e.isRetryableDeviceConnectionError()) {
                        LogKit.w(
                            AppStrings.ui_list_the_path_request_for_a_sudden_connection_exception_preparing_retry +
                                "path=${request.path}, attempt=$attempt/$PATH_LIST_MAX_ATTEMPTS, error=${e.message}"
                        )
                        delay((PATH_LIST_RETRY_DELAY_MS * attempt).milliseconds)
                        attempt++
                        continue
                    }
                    LogKit.e(AppStrings.ui_list_path_exception_path_arg0_arg1.format(arg0 = (request.path), arg1 = (e.message).toString()), e)
                    finalResult = Result.failure(e)
                }
            }
            finalResult
        }
    }

    fun traversePath(
        path: String,
    ): Flow<Result<List<FileSimpleInfo>>> {
        return traversePath(path = path, requestId = null, batchId = null)
    }

    override fun traversePath(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> {
        return channelFlow {
            val rootPath = normalizeDeviceTraversePath(path)

            manager.requestRegistry.track(requestId, batchId) {
                try {
                    LogKit.i(AppStrings.ui_client_traversal_path_arg0.format(arg0 = (path)))
                    fun currentTransferStatus() = this@PathRouteClient
                        .transferStatus()
                        .clamped()
                        .takeIf { status -> status.sampledAtMillis > 0L }
                    fun isRemoteBusy(): Boolean {
                        val status = currentTransferStatus() ?: return false
                        return status.busy ||
                            (status.maxParallelRequests > 0 && status.activeRequests >= status.maxParallelRequests)
                    }
                    val transferStatus = currentTransferStatus()
                    collectDirectoryEntriesAdaptive(
                        root = FileSimpleInfo.pathFileSimpleInfo(rootPath).withCopy(protocol = FileProtocol.Device),
                        config = resolveTraversalParallelism(
                            endpointKind = TraversalEndpointKind.Device,
                            remoteRecommendedParallelism = transferStatus?.recommendedParallelRequests,
                            remoteBusy = isRemoteBusy(),
                        ),
                        pathSeparator = "/",
                        ensureRunning = {
                            currentCoroutineContext().ensureActive()
                        },
                        onEntriesDiscovered = { entries ->
                            if (entries.isNotEmpty()) {
                                send(Result.success(entries))
                            }
                        },
                        dynamicMaxParallelismProvider = {
                            val latestStatus = currentTransferStatus()
                            resolveTraversalRuntimeMaxParallelism(
                                endpointKind = TraversalEndpointKind.Device,
                                remoteRecommendedParallelism = latestStatus?.recommendedParallelRequests,
                                remoteBusy = isRemoteBusy(),
                            )
                        },
                        requireKnownSymbolicLinkMetadata = true,
                    ) { directory ->
                        listPath(
                            request = ListRequest(directory.path),
                            batchId = batchId,
                        )
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        runCatching { send(Result.failure(e)) }
                    }
                } catch (e: Exception) {
                    LogKit.e(AppStrings.ui_path_traversal_exception_arg0.format(arg0 = (e.message).toString()), e)
                    send(Result.failure(e))
                }
            }
        }
    }

    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo
    ): Result<Boolean> {
        return transferClient.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
    }

    suspend fun writeBytes(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
        fileSimpleInfo: FileSimpleInfo,
        sharedTransferSemaphore: Semaphore = Semaphore(DEVICE_COPY_MAX_CONCURRENT_REQUESTS),
        allowDuplexDeviceTransfer: Boolean = false,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null
    ): Result<Boolean> {
        return transferClient.writeBytes(
            task = task,
            srcFileSimpleInfo = srcFileSimpleInfo,
            destFileSimpleInfo = destFileSimpleInfo,
            fileSimpleInfo = fileSimpleInfo,
            sharedTransferSemaphore = sharedTransferSemaphore,
            allowDuplexDeviceTransfer = allowDuplexDeviceTransfer,
            onProgress = onProgress,
        )
    }

    override suspend fun exists(path: String): Result<Boolean> {
        return exists(path = path, requestId = null, batchId = null)
    }

    suspend fun exists(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_check_path_client_path_arg0.format(arg0 = (path)))
            val request = PathExistsRequest(path)
            val response = httpClient.post("/api/paths/exists") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_check_path_exists_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val exists = decodeHttpSuccessBody<Boolean>(bytes)
            LogKit.d(AppStrings.ui_check_path_exists_successfully_path_arg0_exists_arg1.format(arg0 = (path), arg1 = (exists).toString()))
            Result.success(exists)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_check_path_existence_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun createDirectory(path: String): Result<Boolean> {
        return createDirectory(path = path, requestId = null, batchId = null)
    }

    suspend fun createDirectory(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_create_directory_client_path_arg0.format(arg0 = (path)))
            val request = CreateDirectoryRequest(path)
            val response = httpClient.post("/api/paths/create-directory") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_create_directory_failure_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val success = decodeHttpSuccessBody<Boolean>(bytes)
            LogKit.d(AppStrings.ui_directory_created_successfully_path_arg0.format(arg0 = (path)))
            Result.success(success)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_directory_creation_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun deleteDirectory(path: String): Result<Boolean> {
        return deleteDirectory(path = path, requestId = null, batchId = null)
    }

    suspend fun deleteDirectory(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_remove_directory_client_path_arg0.format(arg0 = (path)))
            val request = DeleteDirectoryRequest(path)
            val response = httpClient.post("/api/paths/delete-directory") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_failed_to_delete_directory_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val success = decodeHttpSuccessBody<Boolean>(bytes)
            LogKit.d(AppStrings.ui_deleted_directory_successfully_path_arg0.format(arg0 = (path)))
            Result.success(success)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_remove_directory_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }
}
