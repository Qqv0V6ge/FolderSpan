package com.folderspan.data.main.share

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.path.DevicePathClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 分享会话只暴露共享目录列表与文件读取能力。
 *
 * 复制、任务进度以及本地文件写入由接收端处理，不属于远端分享会话协议。
 */
interface ShareSession {
    val isActive: Boolean

    suspend fun listRoots(): Result<List<FileSimpleInfo>> = list("/")

    suspend fun list(path: String): Result<List<FileSimpleInfo>>

    suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray>

    suspend fun readStream(
        path: String,
        startOffset: Long,
        endOffset: Long,
        onChunk: suspend (ByteArray) -> Unit,
    ): Result<Boolean>

    fun disconnect(): Boolean
}

/**
 * 仅供 Share -> Local 复制桥接 Device 复制引擎使用。
 *
 * Share 对外仍然只暴露 [ShareSession] 的列表与读取能力；底层客户端不会注册成 Device，
 * 也不会进入 Device 抽屉或获得 Share 写权限。
 */
internal interface DeviceBackedShareSession : ShareSession {
    val devicePathClient: DevicePathClient
    val deviceFileClient: DeviceFileClient
}

internal fun traverseShareSession(
    share: Share,
    session: ShareSession,
    rootPath: String,
): Flow<Result<List<FileSimpleInfo>>> = flow {
    val pending = mutableListOf(rootPath)
    val visited = mutableSetOf<String>()
    var index = 0
    while (index < pending.size) {
        val directory = pending[index++]
        if (!visited.add(directory)) continue
        val result = session.list(directory).map { entries ->
            entries.map { entry ->
                entry.withCopy(
                    protocol = FileProtocol.Share,
                    protocolId = share.id,
                )
            }
        }
        emit(result)
        val entries = result.getOrElse { return@flow }
        entries.asSequence()
            .filter { entry ->
                entry.isDirectory && (!entry.isSymbolicLinkKnown || !entry.isSymbolicLink)
            }
            .map { entry -> entry.path }
            .filter { path -> path !in visited }
            .forEach(pending::add)
    }
}
