package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.network.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class AdvancedUpload(
    val connection: String,
    var value: StorageUploadSession,
) {
    val mutex = Mutex()
    suspend fun update(operation: suspend () -> StorageUploadSession) {
        value = try { operation() } catch (uncertain: StorageUploadUncertainException) { uncertain.session }
    }
    fun state(handle: String) = WebUiStorageUploadState(handle, value.target, value.size,
        value.confirmedOffset, value.status, value.entry)
}

internal suspend fun advancedStoragePlatformRequest(
    request: LinkShareHttpRequest,
    session: WebUiPlatformSession,
    connection: suspend (String) -> StorageProvider,
    connectionIdentity: suspend (String) -> String = { error("Storage connection identity is unavailable") },
): LinkShareHttpResponse {
    val operation = request.path.removePrefix("$WEB_UI_PLATFORM_PATH/networks/advanced/")
    if (request.method == "PUT" && operation == "upload-chunk") {
        val handle = requireNotNull(request.query("handle"))
        val upload = session.resource(handle) as? AdvancedUpload ?: error("not an upload handle")
        val bytes = request.boundedBytes()
        val offset = requireNotNull(request.query("offset")?.toLongOrNull())
        return upload.mutex.withLock {
            val client = connection(upload.connection)
            upload.update { client.uploadChunk(upload.value, offset, bytes).getOrThrow() }
            platformResponse(upload.state(handle))
        }
    }
    require(request.method == "POST") { "Unsupported storage operation method" }
    val input = request.platformBody<WebUiStorageAdvancedRequest>()
    if (operation in setOf("upload-query", "upload-finish", "upload-cancel")) {
        val upload = session.resource(input.handle) as? AdvancedUpload ?: error("not an upload handle")
        return upload.mutex.withLock {
            val client = connection(upload.connection)
            when (operation) {
                "upload-query" -> upload.update { client.queryUpload(upload.value).getOrThrow() }
                "upload-finish" -> upload.update { client.finishUpload(upload.value).getOrThrow() }
                else -> {
                    client.cancelUpload(upload.value).getOrThrow()
                    upload.value = upload.value.copy(status = StorageUploadStatus.CANCELLED)
                }
            }
            platformResponse(upload.state(input.handle))
        }
    }
    val client = connection(input.handle)
    if (operation.startsWith("upload-persistence-")) {
        if (operation == "upload-persistence-owner") return platformResponse(client.uploadSessionOwnerId().getOrThrow())
        val reference = requireNotNull(input.uploadReference)
        require(reference.connectionId == connectionIdentity(input.handle)) { "Upload session belongs to another connection" }
        return when (operation) {
            "upload-persistence-save" -> {
                val upload = session.resource(input.uploadHandle) as? AdvancedUpload ?: error("not an upload handle")
                require(upload.connection == input.handle) { "Upload session belongs to another connection" }
                upload.mutex.withLock { client.saveUploadSession(input.taskKey, reference, upload.value).getOrThrow() }
                platformResponse(true)
            }
            "upload-persistence-load" -> {
                val stored = client.loadUploadSession(input.taskKey, reference).getOrThrow()
                if (stored == null) platformResponse<WebUiStorageUploadState?>(null) else {
                    val current = client.queryUpload(stored).getOrThrow()
                    val upload = AdvancedUpload(input.handle, current)
                    val handle = session.own(upload) { upload.mutex.withLock { Unit } }
                    platformResponse<WebUiStorageUploadState?>(upload.state(handle))
                }
            }
            "upload-persistence-delete" -> {
                client.deleteUploadSession(input.taskKey, reference).getOrThrow()
                platformResponse(true)
            }
            else -> LinkShareHttpResponse.text(404, "unknown storage persistence operation")
        }
    }
    return when (operation) {
        "capabilities" -> platformResponse(client.advancedCapabilities(input.path).getOrThrow())
        "versions" -> platformResponse(client.listVersions(input.path, input.cursor).getOrThrow())
        "version-restore" -> platformResponse(client.restoreVersion(requireNotNull(input.version), input.confirmed).getOrThrow())
        "shares" -> platformResponse(client.listShares(input.path, input.cursor).getOrThrow())
        "share-create" -> platformResponse(client.createShare(input.path, requireNotNull(input.shareRequest), input.confirmed).getOrThrow())
        "share-revoke" -> platformResponse(client.revokeShare(requireNotNull(input.share)).getOrThrow())
        "upload-start" -> {
            val upload = AdvancedUpload(input.handle, client.startUpload(input.path, input.size).getOrThrow())
            val handle = session.own(upload) {
                // Close waits for any in-flight writer; remote uncertain/completed results are retained.
                upload.mutex.withLock { Unit }
            }
            platformResponse(upload.state(handle))
        }
        "version-read" -> {
            val version = requireNotNull(input.version)
            val transfer = NetworkTransfer()
            transfer.job = session.scope.async(start = CoroutineStart.LAZY) {
                try {
                    client.readVersion(version) { bytes, total ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val end = minOf(bytes.size, offset + WEB_UI_FILE_STREAM_CHUNK_BYTES)
                            transfer.chunks.send(bytes.copyOfRange(offset, end) to total)
                            offset = end
                        }
                        Result.success(Unit)
                    }.getOrThrow()
                    true
                } catch (error: Throwable) {
                    transfer.chunks.close(error)
                    throw error
                } finally { transfer.chunks.close() }
            }
            val handle = session.own(transfer) {
                transfer.chunks.cancel()
                transfer.job.cancel()
                transfer.job.join()
            }
            transfer.job.start()
            platformResponse(WebUiHandle(handle))
        }
        else -> LinkShareHttpResponse.text(404, "unknown advanced storage operation")
    }
}
