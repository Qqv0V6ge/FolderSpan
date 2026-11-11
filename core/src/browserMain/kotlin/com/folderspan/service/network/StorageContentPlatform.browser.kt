package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.createSettings
import kotlin.uuid.Uuid
import kotlin.io.encoding.Base64
import com.folderspan.service.webui.*
import io.ktor.http.Url
import com.folderspan.exception.AuthorityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual object StorageContentPlatform : StorageContentOperations {
    private data class Context(
        val target: WebUiRuntimeAddress,
        val token: String,
        val connection: WebUiManagementConnection?,
    ) {
        fun requireCurrent() {
            check(WebUiAttachment.target === target && WebUiAttachment.sessionToken == token &&
                WebUiPlatformClient.connection === connection) { "Runtime encryption context changed" }
            check(token.isNotBlank() && connection != null && !connection.closed.isCompleted) {
                "Authenticated Runtime management connection required"
            }
        }
    }

    private val mutex = Mutex()
    private var contextHandle: String? = null
    private var owner: Context? = null
    private data class PrivatePath(val context: Context, val handle: String, val taskKey: Long,
        val entryId: String, val writable: Boolean)
    private val privatePaths = mutableMapOf<String, PrivatePath>()

    private fun rememberPrivatePath(path: String, taskKey: Long, entryId: String, writable: Boolean) {
        val current = checkNotNull(owner)
        current.requireCurrent()
        privatePaths[path] = PrivatePath(current, checkNotNull(contextHandle), taskKey, entryId, writable)
    }

    internal fun ownsPrivatePath(path: String): Boolean = path in privatePaths

    private fun privatePath(path: String, taskKey: Long? = null, writing: Boolean = false): PrivatePath {
        val access = checkNotNull(privatePaths[path]) { "Unknown private content path" }
        access.context.requireCurrent()
        check(taskKey == null || access.taskKey == taskKey) { "Private content task changed" }
        check(!writing || access.writable) { "Encrypted upload sources are read-only" }
        return access
    }

    private suspend fun privateFile(method: String, path: String, arguments: String = "", body: ByteArray? = null): WebUiHttpExchange {
        val access = privatePath(path, writing = method != "GET")
        val query = "?handle=" + encodeWebUiQuery(access.handle) + "&taskKey=" + access.taskKey +
            "&taskReference=" + encodeWebUiQuery(taskReference(access.taskKey)) + "&path=" + encodeWebUiQuery(path) + arguments
        val response = WebUiPlatformClient.exchange(method, "$WEB_UI_PLATFORM_PATH/storage/content/private-file$query",
            body, if (body != null) "application/octet-stream" else null)
        access.context.requireCurrent()
        return response
    }

    internal suspend fun privateFileInfo(path: String): FileSimpleInfo =
        WebUiPlatformClient.json.decodeFromString(privateFile("GET", path, "&metadata=true").body.decodeToString())

    internal suspend fun readPrivateRange(path: String, start: Long, end: Long): ByteArray {
        require(start >= 0 && end >= start && end - start <= WEB_UI_FILE_STREAM_CHUNK_BYTES)
        return privateFile("GET", path, "&offset=$start&length=" + (end - start)).body
    }

    internal suspend fun writePrivateRange(path: String, size: Long, bytes: ByteArray, offset: Long) {
        require(bytes.size <= WEB_UI_FILE_STREAM_CHUNK_BYTES)
        privateFile("PUT", path, "&offset=$offset&size=$size", bytes)
    }

    internal suspend fun deletePrivateFile(path: String): Boolean {
        val deleted = WebUiPlatformClient.json.decodeFromString<Boolean>(privateFile("DELETE", path).body.decodeToString())
        privatePaths.remove(path)
        return deleted
    }

    private suspend fun handle(expected: Context): String = mutex.withLock {
        expected.requireCurrent()
        if (owner != expected) { contextHandle = null; owner = expected; privatePaths.clear() }
        contextHandle ?: WebUiPlatformClient.post<WebUiHandle, WebUiHandle>(
            "/storage/content/open", WebUiHandle("")).id.also {
                expected.requireCurrent()
                contextHandle = it
            }
    }

    private fun requireProtectedPasswordTransport(target: WebUiRuntimeAddress) {
        val url = Url(target.httpUrl("/"))
        require(url.protocol.name == "https" || isStorageContentLoopbackHost(url.host)) {
            "Content passwords require HTTPS or a local Runtime"
        }
    }

    private fun taskReference(taskKey: Long): String {
        val runtime = WebUiAttachment.target?.httpUrl("/").orEmpty()
        val key = "storage.content.task." + Base64.UrlSafe.encode(runtime.encodeToByteArray()) + "." + taskKey
        val settings = createSettings()
        return settings.getStringOrNull(key) ?: Uuid.random().toString().also { settings.putString(key, it) }
    }

    private suspend inline fun <reified T> request(
        operation: String,
        input: (String) -> WebUiStorageContentRequest,
    ): T {
        val expected = Context(checkNotNull(WebUiAttachment.target) { "Select a Runtime before using content encryption" },
            WebUiAttachment.sessionToken, WebUiPlatformClient.connection)
        if (operation == "setup-password" || operation == "unlock-password") requireProtectedPasswordTransport(expected.target)
        val handle = handle(expected)
        expected.requireCurrent()
        val bytes = WebUiPlatformClient.json.encodeToString(WebUiStorageContentRequest.serializer(), input(handle)).encodeToByteArray()
        return try {
            expected.requireCurrent()
            try {
                val response = WebUiPlatformClient.exchange("POST", "$WEB_UI_PLATFORM_PATH/storage/content/$operation", bytes, "application/json")
                expected.requireCurrent()
                WebUiPlatformClient.json.decodeFromString<T>(response.body.decodeToString())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: StorageContentException) { throw error }
            catch (error: AuthorityException) { throw error }
            catch (error: com.folderspan.service.mcp.file.FileEndpointException) { throw error }
            catch (error: Exception) {
                if (operation in setOf("setup-password", "begin", "prepare", "record", "publish", "rename", "delete", "mkdir")) {
                    throw StorageContentException(StorageContentErrorCode.UncertainResult,
                        "Content operation result is uncertain; reconnect to the original Runtime and verify it before retrying")
                }
                throw error
            }
        } finally { bytes.fill(0) }
    }

    override suspend fun inspectPasswordLocation(config: NetworkConnectionConfig): Result<StorageContentLocationStatus> = platformResult {
        request("inspect") { WebUiStorageContentRequest(it, config) }
    }
    override suspend fun setupPassword(config: NetworkConnectionConfig, password: CharArray): Result<StorageContentLocationReference> = platformResult {
        request("setup-password") { WebUiStorageContentRequest(it, config, password = password.concatToString()) }
    }
    override suspend fun unlockPassword(config: NetworkConnectionConfig, password: CharArray): Result<Unit> = platformResult {
        check(request<Boolean>("unlock-password") { WebUiStorageContentRequest(it, config, password = password.concatToString()) })
    }
    override suspend fun status(config: NetworkConnectionConfig): Result<StorageContentStatus> = platformResult {
        request("status") { WebUiStorageContentRequest(it, config) }
    }
    override suspend fun create(config: NetworkConnectionConfig): Result<Unit> = unsupportedRecovery()
    override suspend fun exportRecovery(config: NetworkConnectionConfig, password: CharArray): Result<ByteArray> = unsupportedRecovery()
    override suspend fun verifyBackupAndEnable(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = unsupportedRecovery()
    override suspend fun importRecovery(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = unsupportedRecovery()
    override suspend fun unlock(config: NetworkConnectionConfig): Result<Unit> = platformResult {
        error("Enter the content password to unlock this location")
    }
    private fun <T> unsupportedRecovery(): Result<T> =
        Result.failure(UnsupportedOperationException("Recovery files are not supported; use the location content password"))

    override suspend fun lock(config: NetworkConnectionConfig): Result<Unit> = platformResult {
        check(request<Boolean>("lock") { WebUiStorageContentRequest(it, config) })
    }
    override suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit> = platformResult {
        check(request<Boolean>("enabled") { WebUiStorageContentRequest(it, config, enabled = enabled) })
    }
    override suspend fun createStage(taskKey: Long, entryId: String): Result<String> = platformResult {
        request<String>("stage") { WebUiStorageContentRequest(it, taskKey = taskKey, entryId = entryId, taskReference = taskReference(taskKey)) }
            .also { rememberPrivatePath(it, taskKey, entryId, true) }
    }
    override suspend fun stageIdentity(taskKey: Long, path: String): Result<String> = platformResult {
        privatePath(path, taskKey)
        request("stage-identity") { WebUiStorageContentRequest(it, taskKey = taskKey, stagedPath = path, taskReference = taskReference(taskKey)) }
    }
    override suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String): Result<Unit> = platformResult {
        privatePath(path, taskKey, writing = true)
        check(request<Boolean>("stage-complete") { WebUiStorageContentRequest(it, taskKey = taskKey,
            stagedPath = path, taskReference = taskReference(taskKey), expectedIdentity = expectedIdentity) })
    }
    override suspend fun prepareUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String): Result<StoragePreparedContentUpload?> = platformResult {
        privatePath(stagedPath, taskKey, writing = true)
        val prepared = request<StoragePreparedContentUpload?>("prepare") {
            WebUiStorageContentRequest(it, config, taskKey, entryId, source, stagedPath, taskReference = taskReference(taskKey))
        }
        privatePaths.entries.removeAll { it.value.taskKey == taskKey && it.value.entryId == entryId && !it.value.writable }
        if (prepared != null && !prepared.uploaded && !prepared.committed && prepared.path.isNotBlank()) {
            rememberPrivatePath(prepared.path, taskKey, entryId, false)
        }
        prepared
    }
    override suspend fun beforeMutation(config: NetworkConnectionConfig, path: StoragePath): Result<Unit> = platformResult {
        check(request<Boolean>("mutation") { WebUiStorageContentRequest(it, config, target = path) })
    }
    override suspend fun uploadEncryptionEnabled(config: NetworkConnectionConfig, taskKey: Long, entryId: String): Result<Boolean> = platformResult {
        request("upload-encryption") { WebUiStorageContentRequest(it, config, taskKey, entryId, taskReference = taskReference(taskKey)) }
    }
    override suspend fun resolveUploadTarget(config: NetworkConnectionConfig, target: StoragePath): Result<StoragePath> = platformResult {
        request("upload-target") { WebUiStorageContentRequest(it, config, target = target) }
    }
    override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String, target: StoragePath): Result<Unit> = platformResult {
        check(request<Boolean>("begin") { WebUiStorageContentRequest(it, config, taskKey, entryId,
            taskReference = taskReference(taskKey), target = target) })
    }
    override suspend fun publishDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String): Result<Boolean> = platformResult {
        privatePath(stagedPath, taskKey, writing = true)
        if (ownsPrivatePath(destinationPath)) privatePath(destinationPath, taskKey, writing = true)
        request("publish") { WebUiStorageContentRequest(it, config, taskKey, file = file,
            stagedPath = stagedPath, destinationPath = destinationPath, taskReference = taskReference(taskKey)) }
    }
    override suspend fun recordUploaded(config: NetworkConnectionConfig, taskKey: Long, entryId: String, entry: NetworkFileEntry): Result<Unit> = platformResult {
        check(request<Boolean>("record") { WebUiStorageContentRequest(it, config, taskKey, entryId, entry = entry, taskReference = taskReference(taskKey)) })
    }
    override suspend fun logicalFile(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<FileSimpleInfo?> = platformResult {
        request("logical") { WebUiStorageContentRequest(it, config, file = file) }
    }
    override suspend fun logicalList(config: NetworkConnectionConfig, directory: StoragePath): Result<List<NetworkFileEntry>?> = platformResult {
        request("list") { WebUiStorageContentRequest(it, config, target = directory) }
    }
    override suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<StorageContentDownload?> = platformResult {
        request("resolve-download") { WebUiStorageContentRequest(it, config, file = file) }
    }
    override suspend fun renameLogical(config: NetworkConnectionConfig, source: StoragePath, target: StoragePath): Result<Boolean?> = platformResult {
        request("rename") { WebUiStorageContentRequest(it, config, target = source, destination = target) }
    }
    override suspend fun deleteLogical(config: NetworkConnectionConfig, path: StoragePath, isDirectory: Boolean): Result<Boolean?> = platformResult {
        request("delete") { WebUiStorageContentRequest(it, config, target = path, isDirectory = isDirectory) }
    }
    override suspend fun createLogicalDirectory(config: NetworkConnectionConfig, path: StoragePath): Result<Boolean?> = platformResult {
        request("mkdir") { WebUiStorageContentRequest(it, config, target = path) }
    }
    override suspend fun cleanup(taskKey: Long): Result<Unit> = platformResult {
        check(request<Boolean>("cleanup") { WebUiStorageContentRequest(it, taskKey = taskKey, taskReference = taskReference(taskKey)) })
        privatePaths.entries.removeAll { it.value.taskKey == taskKey }
    }
}
