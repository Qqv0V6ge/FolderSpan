package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.FtpPathEncoding
import com.folderspan.data.main.network.Network
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.*
import java.io.*

internal class FtpNetworkClient(private val network: Network) : NetworkClient, ChunkReadableNetworkClient {
    private suspend fun <T> withClient(block: suspend (FTPClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            val key = networkSessionKey(network, 21)
            Result.success(sessions.use(key, { connectClient(key) }, block))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LogKit.w(AppStrings.ui_ftp_operation_failed_arg0.format(arg0 = error.message.toString()), error)
            Result.failure(error)
        }
    }

    private fun connectClient(key: NetworkSessionKey): FTPClient {
        val extras = network.extras.ftp
        val client = createFtpTransportClient(extras.ftpsEnabled)
        var connected = false
        try {
            client.connectTimeout = 10_000
            client.defaultTimeout = 10_000
            client.controlEncoding = extras.pathEncoding.toControlEncoding()
            client.autodetectUTF8 = extras.pathEncoding == FtpPathEncoding.Auto
            LogKit.i(AppStrings.ui_ftp_connection_arg0_arg1_passive_arg2_ftps_arg3_encoding.format(
                arg0 = key.host, arg1 = key.port.toString(), arg2 = extras.passiveMode.toString(),
                arg3 = extras.ftpsEnabled.toString(), arg4 = extras.pathEncoding.toString(),
            ))
            client.connect(key.host, key.port)
            check(FTPReply.isPositiveCompletion(client.replyCode)) { client.replyString }
            check(client.login(network.username.ifBlank { "anonymous" }, network.password)) { AppStrings.ui_ftp_login_failed }
            if (extras.passiveMode) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
            check(client.setFileType(FTP.BINARY_FILE_TYPE)) { client.replyString }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            LogKit.i(AppStrings.ui_ftp_connected_arg0_arg1.format(arg0 = key.host, arg1 = key.port.toString()))
            return client.also { connected = true }
        } finally {
            if (!connected) runCatching { client.disconnect() }
        }
    }

    companion object {
        private val sessions = NetworkSessionCache<FTPClient>(
            // FTP 控制连接由一个操作独占，NOOP 验证服务器尚未关闭空闲连接。
            isOpen = { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) },
            close = { withContext(Dispatchers.IO) { runCatching { it.disconnect() }; Unit } },
            // 中断传输可能留下未消费的完成响应，不能将这样的连接交给下一个操作。
            isConnectionFailure = { true },
            exclusive = true,
        )
    }

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        return withClient { client ->
            val target = normalizePath(path)
            val files = client.listFiles(target) ?: emptyArray()
            check(FTPReply.isPositiveCompletion(client.replyCode)) { client.replyString }
            val entries = files.filterNot { isUnsafeNetworkPathSegment(it.name) }
                .map { file ->
                    NetworkFileEntry(
                        name = file.name,
                        path = joinPath(target, file),
                        isDirectory = file.isDirectory,
                        size = if (file.isDirectory) -1 else file.size,
                        createdDate = file.timestamp?.time?.time ?: 0L,
                        updatedDate = file.timestamp?.time?.time ?: 0L,
                        isHidden = file.name.startsWith("."),
                        isSymbolicLink = file.isSymbolicLink,
                        isSymbolicLinkKnown = !file.isUnknown,
                    )
                }
            LogKit.i(AppStrings.ui_ftp_list_arg0_count_arg1.format(arg0 = target, arg1 = (entries.size).toString()))
            entries
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return withClient { client ->
            openLocalSinkNoFollow(localPath).use { channel ->
                java.nio.channels.Channels.newOutputStream(channel).use { output ->
                    val progressOutput = ProgressOutputStream(output, size, onProgress)
                    val success = client.retrieveFile(normalizePath(remotePath), progressOutput)
                    progressOutput.flush()
                    if (!success) {
                        throw IllegalStateException(AppStrings.ui_ftp_download_failed_arg0.format(arg0 = (client.replyString).toString()))
                    }
                }
            }
            true
        }
    }

    override suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        return withClient { client ->
            val success = client.retrieveFileStream(normalizePath(remotePath))?.use { input ->
                relayStreamByChunks(input, size.coerceAtLeast(0L), onChunk).getOrElse { error ->
                    throw error
                }
                true
            } ?: false
            val completed = success && client.completePendingCommand()
            if (!completed) {
                throw IllegalStateException(AppStrings.ui_ftp_stream_download_failed_arg0.format(arg0 = (client.replyString).toString()))
            }
            true
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return openLocalSourceNoFollow(localPath).use { channel ->
            withClient { client ->
                java.nio.channels.Channels.newInputStream(channel).use { input ->
                    val progressInput = ProgressInputStream(input, size, onProgress)
                    val success = client.storeFile(normalizePath(remotePath), progressInput)
                    if (!success) {
                        throw IllegalStateException(AppStrings.ui_ftp_upload_failed_arg0.format(arg0 = (client.replyString).toString()))
                    }
                }
                true
            }
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        return withClient { client ->
            PullChunkInputStream(readChunk).use { input ->
                val progressInput = ProgressInputStream(input, size, onProgress)
                val success = client.storeFile(normalizePath(remotePath), progressInput)
                if (!success) {
                    throw IllegalStateException(AppStrings.ui_ftp_upload_failed_arg0.format(arg0 = (client.replyString).toString()))
                }
            }
            true
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        return withClient { client ->
            copyFileWithFtpSiteCommands(
                client = client,
                sourcePath = normalizePath(sourcePath),
                targetPath = normalizePath(targetPath),
            )
            true
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return withClient { client ->
            client.rename(normalizePath(path), normalizePath(newPath))
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return withClient { client ->
            if (isDirectory) {
                client.removeDirectory(normalizePath(path))
            } else {
                client.deleteFile(normalizePath(path))
            }
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return withClient { client ->
            client.makeDirectory(normalizePath(path))
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withClient { client ->
            val empty = ByteArrayInputStream(ByteArray(0))
            client.storeFile(normalizePath(path), empty)
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return network.pathSeparator
        requireSafeNetworkWritePath(path)
        return path
    }

    private fun joinPath(base: String, file: FTPFile): String {
        require(!isUnsafeNetworkPathSegment(file.name)) { AppStrings.ui_remote_path_invalid }
        val separator = network.pathSeparator
        val normalized = if (base.endsWith(separator)) base else base + separator
        return normalized + file.name
    }

}

internal fun createFtpTransportClient(ftpsEnabled: Boolean): FTPClient {
    return if (ftpsEnabled) {
        FTPSClient().apply { isEndpointCheckingEnabled = true }
    } else {
        FTPClient()
    }
}

internal fun copyFileWithFtpSiteCommands(
    client: FTPClient,
    sourcePath: String,
    targetPath: String,
) {
    require(
        !sourcePath.contains('\r') &&
            !sourcePath.contains('\n') &&
            !targetPath.contains('\r') &&
            !targetPath.contains('\n')
    ) {
        AppStrings.ui_ftp_server_copy_path_cannot_contain_newline_characters
    }
    val copyFromReply = client.sendCommand("SITE", "CPFR $sourcePath")
    if (
        isFtpServerCopyUnsupportedReply(copyFromReply) ||
        !FTPReply.isPositiveIntermediate(copyFromReply) &&
        !FTPReply.isPositiveCompletion(copyFromReply)
    ) {
        throwFtpServerCopyFailure("CPFR", copyFromReply, client.replyString)
    }

    val copyToReply = client.sendCommand("SITE", "CPTO $targetPath")
    if (isFtpServerCopyUnsupportedReply(copyToReply) || !FTPReply.isPositiveCompletion(copyToReply)) {
        throwFtpServerCopyFailure("CPTO", copyToReply, client.replyString)
    }
}

private fun throwFtpServerCopyFailure(command: String, replyCode: Int, reply: String): Nothing {
    val message = AppStrings.ui_ftp_server_rejected_site_arg0_arg1.format(arg0 = command, arg1 = reply.trim())
    if (isFtpServerCopyUnsupportedReply(replyCode)) {
        throw NetworkUnsupportedException(AppStrings.ui_unable_to_execute_ftp_server_replication_arg0.format(arg0 = (message)))
    }
    throw IllegalStateException(message)
}

private fun isFtpServerCopyUnsupportedReply(replyCode: Int): Boolean =
    replyCode == 202 || replyCode == 500 || replyCode == 502 || replyCode == 504

private class ProgressInputStream(
    input: InputStream,
    private val totalBytes: Long,
    private val onProgress: (Long, Long) -> Unit
) : FilterInputStream(input) {
    private var doneBytes = 0L

    override fun read(): Int {
        val result = super.read()
        if (result >= 0) {
            doneBytes++
            onProgress(doneBytes, totalBytes)
        }
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) {
            doneBytes += read
            onProgress(doneBytes, totalBytes)
        }
        return read
    }
}

private class ProgressOutputStream(
    output: OutputStream,
    private val totalBytes: Long,
    private val onProgress: (Long, Long) -> Unit
) : FilterOutputStream(output) {
    private var doneBytes = 0L

    override fun write(b: Int) {
        out.write(b)
        doneBytes++
        onProgress(doneBytes, totalBytes)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        if (len > 0) {
            doneBytes += len
            onProgress(doneBytes, totalBytes)
        }
    }
}


private fun FtpPathEncoding.toControlEncoding(): String {
    return when (this) {
        FtpPathEncoding.Auto -> "UTF-8"
        FtpPathEncoding.Utf8 -> "UTF-8"
        FtpPathEncoding.Gb18030 -> "GB18030"
        FtpPathEncoding.ShiftJis -> "Shift_JIS"
        FtpPathEncoding.Cp949 -> "CP949"
        FtpPathEncoding.Windows1251 -> "windows-1251"
    }
}
