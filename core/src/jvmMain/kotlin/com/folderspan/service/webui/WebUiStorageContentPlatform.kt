package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.network.StorageContentHostContext
import com.folderspan.service.network.StorageContentException
import com.folderspan.service.network.StorageContentErrorCode
import com.folderspan.service.network.StorageUploadSessions
import com.folderspan.createSettings
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.openLocalSourceNoFollow
import com.folderspan.ui.state.main.TaskUploadSourceIdentity
import com.folderspan.utils.NoFollowFileChannels
import com.folderspan.utils.isWriteRangeWithinFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class SessionContent(val context: StorageContentHostContext, val resolveTask: (Long, String) -> Long) {
    val mutex = Mutex()
    val stages = mutableMapOf<String, Long>()
    val cipherSources = mutableMapOf<String, Pair<Long, String>>()
    val tasks = mutableMapOf<Long, Pair<String, Long>>()
    fun task(input: WebUiStorageContentRequest, create: Boolean = false): Long {
        require(runCatching { UUID.fromString(input.taskReference).toString() == input.taskReference }.getOrDefault(false)) {
            "Invalid content task reference"
        }
        val existing = tasks[input.taskKey]
        if (existing != null) {
            require(existing.first == input.taskReference) { "Content task reference changed" }
            return existing.second
        }
        require(create) { "Unknown content encryption task" }
        val mapped = resolveTask(input.taskKey, input.taskReference)
        tasks[input.taskKey] = input.taskReference to mapped
        return mapped
    }
    fun close() = context.close()
}

@Serializable
private data class ContentTaskMapping(val version: Int = 1, val taskKey: Long, val reference: String, val runtimeTaskKey: Long)
private val contentTaskMappingLock = Any()
private fun contentRuntimeTaskId(taskKey: Long, reference: String): Long = synchronized(contentTaskMappingLock) {
    val settings = createSettings()
    val name = "auth.storage.content.task." + reference
    val existing = settings.getStringOrNull(name)?.let { Json.decodeFromString<ContentTaskMapping>(it) }
    if (existing != null) {
        require(existing.version == 1 && existing.taskKey == taskKey && existing.reference == reference) { "Content task identity changed" }
        existing.runtimeTaskKey
    } else {
        val value = UUID.randomUUID().mostSignificantBits
        settings.putString(name, Json.encodeToString(ContentTaskMapping(taskKey = taskKey, reference = reference, runtimeTaskKey = value)))
        value
    }
}

internal suspend fun storageContentPlatformRequest(request: LinkShareHttpRequest,
    session: WebUiPlatformSession,
    createContext: () -> StorageContentHostContext = { StorageContentHostContext() },
    resolveTask: (Long, String) -> Long = ::contentRuntimeTaskId,
): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/storage/content/")
    return try {
        if (operation == "private-file") return privateContentFile(request, session)
        require(request.method == "POST") { "Unsupported content encryption method" }
        if (operation == "open") {
            val context = SessionContent(createContext(), resolveTask)
            return platformResponse(WebUiHandle(session.own(context) { context.mutex.withLock { context.close() } }))
        }
        if (operation in setOf("create", "export", "verify", "import", "unlock")) {
            throw StorageContentException(StorageContentErrorCode.Unavailable,
                "Use the location content password; recovery files are not supported")
        }
        if (operation == "setup-password" || operation == "unlock-password") {
            require(request.queryParameters.isEmpty()) { "Content passwords must use the request body" }
            require(request.scheme == "https" || request.scheme == "http" &&
                isStorageContentLoopbackHost(request.host) &&
                request.remoteHost in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
                "Content passwords require HTTPS or a local Runtime"
            }
        }
        val bytes = request.boundedBytes()
        val input = try {
            try { platformJson.decodeFromString<WebUiStorageContentRequest>(bytes.decodeToString()) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid content encryption request") }
        } finally { bytes.fill(0) }
        require(input.password.isEmpty() || operation == "setup-password" || operation == "unlock-password") {
            "Content password is not valid for this operation"
        }
        require(input.password.length <= 1024) { "Content password is too long" }
        val resource = session.resource(input.handle) as? SessionContent ?: error("Not a content encryption handle")
        resource.mutex.withLock {
            val context = resource.context
            if (operation == "stage") {
                val path = context.createStage(resource.task(input, create = true), input.entryId).getOrThrow()
                resource.stages[path] = resource.task(input)
                return@withLock platformResponse(path)
            }
            if (operation == "stage-identity" || operation == "stage-complete") {
                val task = resource.task(input)
                val plain = resource.stages[input.stagedPath] == task
                require(plain || resource.cipherSources[input.stagedPath]?.first == task) { "Unknown private content stage" }
                if (operation == "stage-identity") {
                    return@withLock platformResponse(context.stageIdentity(task, input.stagedPath).getOrThrow())
                }
                require(plain) { "Encrypted upload sources are read-only" }
                context.completeStageWrite(task, input.stagedPath, input.expectedIdentity).getOrThrow()
                return@withLock platformResponse(true)
            }
            if (operation == "cleanup") {
                val task = resource.task(input)
                check(!StorageUploadSessions.store.hasUncertainTask(task)) { "Storage upload completion is uncertain; preserve content evidence" }
                context.cleanup(task).getOrThrow()
                resource.stages.entries.removeAll { it.value == task }
                resource.cipherSources.entries.removeAll { it.value.first == task }
                resource.tasks.remove(input.taskKey)
                return@withLock platformResponse(true)
            }
            val config = requireNotNull(input.config)
            when (operation) {
                "inspect" -> platformResponse(context.inspectPasswordLocation(config).getOrThrow())
                "setup-password" -> {
                    val password = input.password.toCharArray()
                    try { platformResponse(context.setupPassword(config, password).getOrThrow()) }
                    finally { password.fill('\u0000') }
                }
                "unlock-password" -> {
                    val password = input.password.toCharArray()
                    try {
                        context.unlockPassword(config, password).getOrThrow()
                        platformResponse(true)
                    } finally { password.fill('\u0000') }
                }
                "status" -> platformResponse(context.status(config).getOrThrow())
                "lock" -> { context.lock(config).getOrThrow(); platformResponse(true) }
                "enabled" -> { context.setEnabled(config, input.enabled).getOrThrow(); platformResponse(true) }
                "logical" -> platformResponse(context.logicalFile(config, requireNotNull(input.file)).getOrThrow())
                "list" -> platformResponse(context.logicalList(config, requireNotNull(input.target)).getOrThrow())
                "resolve-download" -> platformResponse(context.resolveDownload(config, requireNotNull(input.file)).getOrThrow())
                "rename" -> platformResponse(context.renameLogical(config, requireNotNull(input.target), requireNotNull(input.destination)).getOrThrow())
                "delete" -> platformResponse(context.deleteLogical(config, requireNotNull(input.target), input.isDirectory).getOrThrow())
                "mkdir" -> platformResponse(context.createLogicalDirectory(config, requireNotNull(input.target)).getOrThrow())
                "mutation" -> {
                    context.beforeMutation(config, requireNotNull(input.target)).getOrThrow()
                    platformResponse(true)
                }
                "upload-encryption" -> platformResponse(context.uploadEncryptionEnabled(config,
                    resource.task(input, create = true), input.entryId).getOrThrow())
                "upload-target" -> platformResponse(context.resolveUploadTarget(config, requireNotNull(input.target)).getOrThrow())
                "begin" -> {
                    context.beginUpload(config, resource.task(input, create = true), input.entryId, requireNotNull(input.target)).getOrThrow()
                    platformResponse(true)
                }
                "record" -> {
                    require(input.taskKey in resource.tasks) { "Unknown content encryption task" }
                    context.recordUploaded(config, resource.task(input), input.entryId, requireNotNull(input.entry)).getOrThrow()
                    platformResponse(true)
                }
                "prepare" -> {
                    val task = resource.task(input)
                    require(resource.stages[input.stagedPath] == task) { "Unknown private content stage" }
                    context.stageIdentity(task, input.stagedPath).getOrThrow()
                    val prepared = context.prepareUpload(config, task, input.entryId, requireNotNull(input.file), input.stagedPath).getOrThrow()
                    resource.cipherSources.entries.removeAll { it.value == (task to input.entryId) }
                    if (prepared != null && !prepared.uploaded && !prepared.committed) {
                        require(prepared.path.isNotBlank()) { "Encrypted staging source is unavailable" }
                        context.stageIdentity(task, prepared.path).getOrThrow()
                        resource.cipherSources[prepared.path] = task to input.entryId
                    }
                    platformResponse(if (prepared?.uploaded == true || prepared?.committed == true) prepared.copy(path = "") else prepared)
                }
                "publish" -> {
                    val task = resource.task(input)
                    require(resource.stages[input.stagedPath] == task) { "Unknown private content stage" }
                    context.stageIdentity(task, input.stagedPath).getOrThrow()
                    val target = if (input.destinationPath in resource.stages) {
                        require(resource.stages[input.destinationPath] == task) { "Content destination belongs to another task" }
                        context.stageIdentity(task, input.destinationPath).getOrThrow()
                        input.destinationPath
                    } else checkedPlatformPath(input.destinationPath, allowMissing = true, rejectSymbolicLinks = true)
                    platformResponse(context.publishDownload(config, task, requireNotNull(input.file), input.stagedPath, target).getOrThrow())
                }
                else -> error("Unknown content encryption operation")
            }
        }
    } catch (error: StorageContentException) {
        LinkShareHttpResponse.text(statusCode = 400, contentType = "application/json",
            text = platformJson.encodeToString(WebUiJsonError(error.message.orEmpty(), STORAGE_CONTENT_ERROR_PREFIX + error.code.name)))
    }
}

private suspend fun privateContentFile(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    require(request.method in setOf("GET", "PUT", "DELETE")) { "Unsupported private content file method" }
    val input = WebUiStorageContentRequest(
        handle = requireNotNull(request.query("handle")),
        taskKey = requireNotNull(request.query("taskKey")?.toLongOrNull()),
        taskReference = requireNotNull(request.query("taskReference")),
        stagedPath = requireNotNull(request.query("path")),
    )
    val resource = session.resource(input.handle) as? SessionContent ?: error("Not a content encryption handle")
    return resource.mutex.withLock {
        val task = resource.task(input)
        val path = input.stagedPath
        val plain = resource.stages[path] == task
        require(plain || resource.cipherSources[path]?.first == task) { "Unknown private content stage" }
        require(request.method == "GET" || plain) { "Encrypted upload sources are read-only" }
        withContext(Dispatchers.IO) {
            if (request.method == "DELETE" && !Files.exists(Path.of(path), LinkOption.NOFOLLOW_LINKS)) {
                return@withContext platformResponse(true)
            }
            val identity = resource.context.stageIdentity(task, path).getOrThrow()
            val grant = TaskUploadSourceIdentity(FileSimpleInfo.pathFileSimpleInfo(path), path, identity, task, resource.context, plain)
            when (request.method) {
                "GET" -> withContext(grant) {
                    openLocalSourceNoFollow(path).use { channel ->
                        val size = channel.size()
                        if (request.query("metadata") == "true") {
                            platformResponse(FileSimpleInfo(Path.of(path).fileName.toString(), isDirectory = false,
                                isHidden = true, path = path, mineType = "application/octet-stream", size = size,
                                createdDate = 0, updatedDate = 0))
                        } else {
                            val offset = requireNotNull(request.query("offset")?.toLongOrNull())
                            val length = requireNotNull(request.query("length")?.toIntOrNull())
                            require(offset in 0..size && length in 0..WEB_UI_FILE_STREAM_CHUNK_BYTES && length.toLong() <= size - offset) { "Invalid private file range" }
                            val buffer = ByteBuffer.allocate(length)
                            while (buffer.hasRemaining()) {
                                check(channel.read(buffer, offset + buffer.position()) > 0) { "Private staging source changed" }
                            }
                            LinkShareHttpResponse.bytes(bytes = buffer.array(), contentType = "application/octet-stream")
                        }
                    }.also { resource.context.stageIdentity(task, path).getOrThrow().let { current -> check(current == identity) } }
                }
                "PUT" -> {
                    val size = requireNotNull(request.query("size")?.toLongOrNull())
                    val offset = requireNotNull(request.query("offset")?.toLongOrNull())
                    val bytes = request.boundedBytes()
                    try {
                        require(isWriteRangeWithinFile(size, offset, bytes.size.toLong())) { "Invalid private write range" }
                        NoFollowFileChannels.openReadWrite(path, create = false).use { channel ->
                            check(resource.context.stageIdentity(task, path).getOrThrow() == identity) { "Private staging ownership changed" }
                            channel.truncate(size)
                            if (channel.size() < size) channel.write(ByteBuffer.wrap(byteArrayOf(0)), size - 1)
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) check(channel.write(buffer, offset + buffer.position()) > 0)
                        }
                        check(resource.context.stageIdentity(task, path).getOrThrow() == identity) { "Private staging ownership changed" }
                        platformResponse(true)
                    } finally { bytes.fill(0) }
                }
                else -> {
                    Files.deleteIfExists(Path.of(path))
                    platformResponse(true)
                }
            }
        }
    }
}
