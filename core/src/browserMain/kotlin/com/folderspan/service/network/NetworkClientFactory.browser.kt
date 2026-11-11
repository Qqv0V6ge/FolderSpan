package com.folderspan.service.network

import com.folderspan.service.webui.*
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.random.Random

actual object NetworkClientFactory {
    actual fun create(network: NetworkConnectionConfig): StorageProvider = HttpNetworkClient(network)
}

private class HttpNetworkClient(private val config: NetworkConnectionConfig) : StorageProvider, ChunkReadableNetworkClient {
    override val capabilities = (if (config.protocol == "HttpDirectory" || config.extras.cloud.readOnly) StorageCapabilities.ReadOnly else StorageCapabilities.ReadWrite)
        .copy(sync = true)
    private val mutex = Mutex()
    private var connection: String? = null
    private var token = ""
    private var callback: String? = null
    private var failure: Throwable? = null

    private suspend fun handle(): String = mutex.withLock {
        failure?.let { throw it }
        if (connection != null) {
            check(token == WebUiAttachment.sessionToken) { "network session expired; reconnect the Runtime" }
            return@withLock checkNotNull(connection)
        }
        token = WebUiAttachment.sessionToken
        val callbackId = "network-${Random.nextLong()}-${Random.nextLong()}"
        callback = callbackId
        WebUiPlatformClient.register(callbackId, { failure = it }) { event ->
            check(event.type == "sftp-host-key") { "unknown network event" }
            val key = WebUiPlatformClient.json.decodeFromString<SftpHostKey>(event.data)
            WebUiPlatformClient.json.encodeToString(SftpHostKeyTrust.shared.confirm(key)).encodeToByteArray()
        }
        try {
            WebUiPlatformClient.post<WebUiNetworkOpen, WebUiHandle>("/networks", WebUiNetworkOpen(callbackId, config)).id.also { connection = it }
        } catch (error: Throwable) {
            WebUiPlatformClient.unregister(callbackId)
            throw error
        }
    }

    override suspend fun close() = mutex.withLock {
        val handle = connection
        connection = null
        callback?.let { WebUiPlatformClient.unregister(it) }
        callback = null
        if (handle != null && token == WebUiAttachment.sessionToken) release(handle)
    }

    override suspend fun effectiveCapabilities(): Result<StorageCapabilities> = platformResult {
        WebUiPlatformClient.post("/networks/capabilities", WebUiNetworkPath(handle(), ""))
    }

    private suspend inline fun <reified T> advanced(operation: String, input: WebUiStorageAdvancedRequest): T =
        WebUiPlatformClient.post("/networks/advanced/$operation", input)

    override suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> = platformResult {
        advanced("capabilities", WebUiStorageAdvancedRequest(handle(), path))
    }

    override suspend fun listVersions(path: StoragePath, cursor: String?): Result<StoragePage<StorageVersion>> = platformResult {
        advanced("versions", WebUiStorageAdvancedRequest(handle(), path, cursor))
    }

    override suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> = platformResult {
        advanced("version-restore", WebUiStorageAdvancedRequest(handle(), version = version, confirmed = confirmed))
    }

    override suspend fun listShares(path: StoragePath, cursor: String?): Result<StoragePage<StorageShare>> = platformResult {
        advanced("shares", WebUiStorageAdvancedRequest(handle(), path, cursor))
    }

    override suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> = platformResult {
        advanced("share-create", WebUiStorageAdvancedRequest(handle(), path, shareRequest = request, confirmed = confirmed))
    }

    override suspend fun revokeShare(share: StorageShare): Result<Unit> = platformResult {
        advanced("share-revoke", WebUiStorageAdvancedRequest(handle(), share = share))
    }

    private suspend fun WebUiStorageUploadState.uploadSession(): StorageUploadSession = StorageUploadSession(
        backend = "Runtime", target = target, size = size, confirmedOffset = confirmedOffset, status = status,
        entry = entry, secret = handle, binding = token + ":" + handle(),
    )

    private suspend fun checkedUpload(session: StorageUploadSession): String {
        check(session.backend == "Runtime" && session.binding == token + ":" + handle()) {
            "Upload belongs to another Runtime session"
        }
        return session.secret
    }

    override suspend fun startUpload(path: StoragePath, size: Long): Result<StorageUploadSession> = platformResult {
        advanced<WebUiStorageUploadState>("upload-start", WebUiStorageAdvancedRequest(handle(), path, size = size)).uploadSession()
    }

    override suspend fun queryUpload(session: StorageUploadSession): Result<StorageUploadSession> = platformResult {
        advanced<WebUiStorageUploadState>("upload-query", WebUiStorageAdvancedRequest(checkedUpload(session))).uploadSession()
    }

    override suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> = platformResult {
        val handle = checkedUpload(session)
        val response = WebUiPlatformClient.exchange("PUT",
            "$WEB_UI_PLATFORM_PATH/networks/advanced/upload-chunk?handle=${encodeWebUiQuery(handle)}&offset=$offset", bytes)
        WebUiPlatformClient.json.decodeFromString<WebUiStorageUploadState>(response.body.decodeToString()).uploadSession()
    }

    override suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> = platformResult {
        advanced<WebUiStorageUploadState>("upload-finish", WebUiStorageAdvancedRequest(checkedUpload(session))).uploadSession()
    }

    override suspend fun cancelUpload(session: StorageUploadSession): Result<Unit> = platformResult {
        check(advanced<WebUiStorageUploadState>("upload-cancel", WebUiStorageAdvancedRequest(checkedUpload(session))).status == StorageUploadStatus.CANCELLED)
    }

    override suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> = platformResult {
        val download = advanced<WebUiHandle>("version-read", WebUiStorageAdvancedRequest(handle(), version = version)).id
        try {
            while (true) {
                val response = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/networks/downloads?handle=${encodeWebUiQuery(download)}")
                if (response.headers["x-folderspan-end"] == "true") break
                check(response.body.isNotEmpty()) { "Invalid storage version stream" }
                onChunk(response.body, response.headers["x-folderspan-size"]?.toLongOrNull() ?: -1).getOrThrow()
            }
            Unit
        } finally { release(download) }
    }

    override suspend fun uploadSessionOwnerId(): Result<String> = platformResult {
        advanced("upload-persistence-owner", WebUiStorageAdvancedRequest(handle()))
    }

    override suspend fun saveUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference,
        session: StorageUploadSession): Result<Unit> = platformResult {
        val upload = checkedUpload(session)
        check(advanced<Boolean>("upload-persistence-save", WebUiStorageAdvancedRequest(
            handle(), taskKey = taskKey, uploadReference = reference, uploadHandle = upload)))
    }

    override suspend fun loadUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference): Result<StorageUploadSession?> = platformResult {
        advanced<WebUiStorageUploadState?>("upload-persistence-load", WebUiStorageAdvancedRequest(
            handle(), taskKey = taskKey, uploadReference = reference))?.uploadSession()
    }

    override suspend fun deleteUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference): Result<Unit> = platformResult {
        check(advanced<Boolean>("upload-persistence-delete", WebUiStorageAdvancedRequest(
            handle(), taskKey = taskKey, uploadReference = reference)))
    }

    override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = platformResult {
        WebUiPlatformClient.post("/networks/stat", WebUiNetworkPath(handle(), path.path, reference = path.reference))
    }

    override suspend fun list(path: String, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = list(StoragePath(path), requestId, batchId)
    override suspend fun list(path: StoragePath, requestId: String?, batchId: String?): Result<List<NetworkFileEntry>> = platformResult {
        val directory = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/directories", WebUiNetworkPath(handle(), path.path, reference = path.reference)).id
        try {
            buildList {
                do {
                    val batch = WebUiPlatformClient.get<WebUiNetworkBatch>("/networks/directories?handle=${encodeWebUiQuery(directory)}")
                    addAll(batch.entries)
                } while (!batch.finished)
            }
        } finally { release(directory) }
    }

    private suspend fun operation(operation: String, path: StoragePath, target: StoragePath = StoragePath(""), directory: Boolean = false): Result<Boolean> = platformResult {
        WebUiPlatformClient.post("/networks/$operation", WebUiNetworkPath(handle(), path.path, target.path, directory, reference = path.reference, newReference = target.reference))
    }
    override suspend fun rename(path: String, newPath: String) = rename(StoragePath(path), newPath)
    override suspend fun rename(path: StoragePath, newPath: String) = operation("rename", path, StoragePath(newPath))
    override suspend fun move(from: StoragePath, to: StoragePath) = operation("move", from, to)
    override suspend fun delete(path: String, isDirectory: Boolean) = delete(StoragePath(path), isDirectory)
    override suspend fun delete(path: StoragePath, isDirectory: Boolean) = operation("delete", path, directory = isDirectory)
    override suspend fun deleteEmptyDirectory(path: String) = deleteEmptyDirectory(StoragePath(path))
    override suspend fun deleteEmptyDirectory(path: StoragePath) = operation("delete-empty-directory", path)
    override suspend fun createFolder(path: String) = createFolder(StoragePath(path))
    override suspend fun createFolder(path: StoragePath) = operation("mkdir", path)
    override suspend fun createFile(path: String) = createFile(StoragePath(path))
    override suspend fun createFile(path: StoragePath) = operation("create", path)
    override suspend fun copyFile(sourcePath: String, targetPath: String) = copyFile(StoragePath(sourcePath), StoragePath(targetPath))
    override suspend fun copyFile(sourcePath: StoragePath, targetPath: StoragePath) = operation("copy", sourcePath, targetPath)

    override suspend fun downloadByChunks(remotePath: String, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = downloadByChunks(StoragePath(remotePath), size, onChunk)

    override suspend fun downloadByChunks(remotePath: StoragePath, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> = platformResult {
        val download = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/download", WebUiNetworkPath(handle(), remotePath.path, size = size, reference = remotePath.reference)).id
        try {
            while (true) {
                val response = WebUiPlatformClient.exchange("GET", "$WEB_UI_PLATFORM_PATH/networks/downloads?handle=${encodeWebUiQuery(download)}")
                if (response.headers["x-folderspan-end"] == "true") break
                check(response.body.isNotEmpty()) { "invalid network stream chunk" }
                onChunk(response.body, response.headers["x-folderspan-size"]?.toLongOrNull() ?: size).getOrThrow()
            }
            true
        } finally { release(download) }
    }

    override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = download(StoragePath(remotePath), localPath, size, onProgress)

    override suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = platformResult {
        FileUtils.writeBytes(FileAccessPermission.Allowed, localPath, 0, byteArrayOf(), 0).getOrThrow()
        var offset = 0L
        downloadByChunks(remotePath, size) { bytes, total ->
            FileUtils.writeBytes(FileAccessPermission.Allowed, localPath, maxOf(total, offset + bytes.size), bytes, offset).map {
                offset += bytes.size
                onProgress(offset, total)
            }
        }.getOrThrow()
    }

    override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> = upload(localPath, StoragePath(remotePath), size, onProgress)

    override suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
        val length = if (size >= 0) size else FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrThrow().size
        var offset = 0L
        return uploadFromSource(remotePath, length, onProgress) {
            if (offset >= length) null else {
                val end = offset + minOf(WEB_UI_FILE_STREAM_CHUNK_BYTES.toLong(), length - offset)
                FileUtils.readFileRange(FileAccessPermission.Allowed, localPath, offset, end).getOrThrow().also { offset = end }
            }
        }
    }

    override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> = uploadFromSource(StoragePath(remotePath), size, onProgress, readChunk)

    override suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?): Result<Boolean> = platformResult {
        val upload = WebUiPlatformClient.post<WebUiNetworkPath, WebUiHandle>("/networks/upload", WebUiNetworkPath(handle(), remotePath.path, size = size, reference = remotePath.reference)).id
        try {
            var sent = 0L
            while (true) {
                val bytes = readChunk() ?: break
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                    WebUiPlatformClient.exchange("PUT", "$WEB_UI_PLATFORM_PATH/networks/uploads?handle=${encodeWebUiQuery(upload)}", bytes.copyOfRange(offset, end), "application/octet-stream")
                    sent += end - offset
                    offset = end
                    onProgress(sent, size)
                }
            }
            WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/networks/uploads?handle=${encodeWebUiQuery(upload)}")
            true
        } finally { release(upload) }
    }

    private suspend fun release(handle: String) = withContext(NonCancellable) {
        if (token == WebUiAttachment.sessionToken) runCatching { WebUiPlatformClient.release(handle) }
        Unit
    }
}
