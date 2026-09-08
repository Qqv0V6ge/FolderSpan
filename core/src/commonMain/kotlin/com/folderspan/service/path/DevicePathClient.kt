package com.folderspan.service.path

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.service.data.ListRequest
import com.folderspan.service.operation.HttpTransferStatus
import kotlinx.coroutines.flow.Flow

interface DevicePathClient {
    /**
     * 返回最近一次路径列表请求携带的设备端负载状态，用于遍历阶段动态收缩或放大并发。
     * 非 HTTP 传输没有服务端状态时保持默认值，调用方会继续使用本机状态和请求耗时调节。
     */
    fun transferStatus(): HttpTransferStatus = HttpTransferStatus.default()

    suspend fun getRootPaths(
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<PathInfo>>

    suspend fun listPath(
        request: ListRequest,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<FileSimpleInfo>>

    fun traversePath(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Flow<Result<List<FileSimpleInfo>>>

    suspend fun exists(path: String): Result<Boolean>

    suspend fun createDirectory(path: String): Result<Boolean>

    suspend fun deleteDirectory(path: String): Result<Boolean>
}

internal fun normalizeDeviceTraversePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    if (trimmed.matches(Regex("^[A-Za-z]:[/\\\\]$"))) return trimmed
    return trimmed.trimEnd('/', '\\')
}
