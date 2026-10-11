package com.folderspan.service.session

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.DeviceBackedShareSession
import com.folderspan.service.data.ListRequest
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.path.DevicePathClient

/**
 * 将公共 Session 传输收窄成只读 Share 会话，避免向分享桌面暴露 Device 写操作。
 */
internal class ReadOnlyShareSessionAdapter(
    override val devicePathClient: DevicePathClient,
    override val deviceFileClient: DeviceFileClient,
    private val active: () -> Boolean,
    private val disconnectSession: () -> Boolean,
) : DeviceBackedShareSession {
    override val isActive: Boolean
        get() = active()

    override suspend fun listRoots(): Result<List<FileSimpleInfo>> = list("/")

    override suspend fun list(path: String): Result<List<FileSimpleInfo>> =
        devicePathClient.listPath(ListRequest(path))

    override suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray> = deviceFileClient.readBytes(path, startOffset, endOffset)

    override suspend fun readStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean> = deviceFileClient.readStream(path, startOffset, endOffset, onChunk)

    override fun disconnect(): Boolean = disconnectSession()
}
