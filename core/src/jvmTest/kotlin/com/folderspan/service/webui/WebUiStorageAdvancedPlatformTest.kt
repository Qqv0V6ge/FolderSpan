package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.*
import com.folderspan.service.network.*
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WebUiStorageAdvancedPlatformTest {
    private class Provider(var uncertain: Boolean = false) : StorageProvider by UnsupportedNetworkClient("unsupported") {
        val store = StorageUploadSessionStore(MapSettings())
        override suspend fun uploadSessionOwnerId() = Result.success(store.ownerRuntimeId)
        override suspend fun saveUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference, session: StorageUploadSession) =
            runCatching { store.save(taskKey, reference, session) }
        override suspend fun loadUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference) =
            runCatching { store.load(taskKey, reference) }
        override suspend fun deleteUploadSession(taskKey: Long, reference: TaskRuntimeUploadSessionReference) =
            runCatching { store.remove(taskKey, reference) }
        var offset = 0L
        override suspend fun startUpload(path: StoragePath, size: Long) = Result.success(
            StorageUploadSession("Test", path, size, 0, secret = "https://secret-upload.example/token", binding = "private-binding"))
        override suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> {
            require(offset == this.offset)
            this.offset += bytes.size
            return Result.success(session.copy(confirmedOffset = this.offset))
        }
        override suspend fun queryUpload(session: StorageUploadSession) = Result.success(session.copy(confirmedOffset = offset))
        override suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> =
            if (uncertain) Result.failure(StorageUploadUncertainException(session.copy(status = StorageUploadStatus.UNCERTAIN)))
            else Result.success(session.copy(status = StorageUploadStatus.COMPLETED))
    }

    private suspend fun request(session: WebUiPlatformSession, provider: Provider, operation: String,
        input: WebUiStorageAdvancedRequest): LinkShareHttpResponse =
        advancedStoragePlatformRequest(LinkShareHttpRequest.from("POST",
            "$WEB_UI_PLATFORM_PATH/networks/advanced/$operation",
            body = LinkShareHttpRequestBody.Bytes(platformJson.encodeToString(input).encodeToByteArray())),
            session, connection = { check(it == "connection-A"); provider }, connectionIdentity = { "connection-A" })

    private fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
    private fun LinkShareHttpResponse.state() = platformJson.decodeFromString<WebUiStorageUploadState>(bytes().decodeToString())

    @Test
    fun sessionHandlesKeepUploadSecretsOnRuntimeAndRejectAnotherSession(): Unit = runBlocking {
        val first = WebUiPlatformSession("first")
        val second = WebUiPlatformSession("second")
        val provider = Provider()
        try {
            val response = request(first, provider, "upload-start",
                WebUiStorageAdvancedRequest("connection-A", StoragePath("/file"), size = 1))
            val encoded = response.bytes().decodeToString()
            assertFalse(encoded.contains("secret-upload"))
            assertFalse(encoded.contains("private-binding"))
            val handle = response.state().handle
            assertFails {
                request(second, provider, "upload-query", WebUiStorageAdvancedRequest(handle))
            }
            val write = LinkShareHttpRequest.from("PUT",
                "$WEB_UI_PLATFORM_PATH/networks/advanced/upload-chunk?handle=$handle&offset=0",
                body = LinkShareHttpRequestBody.Bytes(byteArrayOf(42)))
            val progress = advancedStoragePlatformRequest(write, first, connection = { provider }).state()
            assertEquals(1, progress.confirmedOffset)
            assertEquals(StorageUploadStatus.COMPLETED,
                request(first, provider, "upload-finish", WebUiStorageAdvancedRequest(handle)).state().status)
            first.release(handle)
            assertFails { request(first, provider, "upload-query", WebUiStorageAdvancedRequest(handle)) }
        } finally { first.close(); second.close() }
    }

    @Test
    fun uncertainCommitReturnsSafeStateAndPersistsEvidence(): Unit = runBlocking {
        val session = WebUiPlatformSession("test")
        val provider = Provider(uncertain = true)
        try {
            val upload = request(session, provider, "upload-start",
                WebUiStorageAdvancedRequest("connection-A", StoragePath("/file"), size = 0)).state()
            val result = request(session, provider, "upload-finish", WebUiStorageAdvancedRequest(upload.handle)).state()
            assertEquals(StorageUploadStatus.UNCERTAIN, result.status)
            assertFalse(platformJson.encodeToString(result).contains("secret-upload"))
            val reference = TaskRuntimeUploadSessionReference(
                sessionId = java.util.UUID.randomUUID().toString(), ownerRuntimeId = provider.store.ownerRuntimeId,
                connectionId = "connection-A", source = TaskRuntimeEndpointRef(path = "/source"),
                target = TaskRuntimeEndpointRef(path = "/file"), sourceVersion = "sha256-of-source")
            request(session, provider, "upload-persistence-save", WebUiStorageAdvancedRequest(
                "connection-A", taskKey = 123, uploadReference = reference, uploadHandle = upload.handle))
            assertTrue(provider.store.hasUncertainTask(123))
        } finally { session.close() }
    }

    @Test
    fun persistedUploadReattachesWithNewSessionAndRejectsWrongRuntimeOrConnection(): Unit = runBlocking {
        val first = WebUiPlatformSession("first")
        val second = WebUiPlatformSession("second")
        val provider = Provider()
        try {
            val target = StoragePath("/file")
            val upload = request(first, provider, "upload-start",
                WebUiStorageAdvancedRequest("connection-A", target, size = 1)).state()
            val reference = TaskRuntimeUploadSessionReference(
                sessionId = java.util.UUID.randomUUID().toString(), ownerRuntimeId = provider.store.ownerRuntimeId,
                connectionId = "connection-A", source = TaskRuntimeEndpointRef(path = "/source"),
                target = TaskRuntimeEndpointRef(path = "/file"), sourceVersion = "sha256-of-source")
            request(first, provider, "upload-persistence-save", WebUiStorageAdvancedRequest(
                "connection-A", taskKey = 123, uploadReference = reference, uploadHandle = upload.handle))
            first.close()
            val restored = request(second, provider, "upload-persistence-load", WebUiStorageAdvancedRequest(
                "connection-A", taskKey = 123, uploadReference = reference)).state()
            assertNotEquals(upload.handle, restored.handle)
            assertEquals(target, restored.target)
            assertFails {
                request(second, provider, "upload-query", WebUiStorageAdvancedRequest(upload.handle))
            }
            for (invalid in listOf(reference.copy(ownerRuntimeId = "different-runtime"),
                reference.copy(connectionId = "different-connection"), reference.copy(sourceVersion = "changed"))) {
                assertFails {
                    request(second, provider, "upload-persistence-load", WebUiStorageAdvancedRequest(
                        "connection-A", taskKey = 123, uploadReference = invalid))
                }
            }
        } finally { first.close(); second.close() }
    }

    @Test
    fun oversizedUploadBodiesAndWrongMethodsAreRejectedBeforeWriting(): Unit = runBlocking {
        val session = WebUiPlatformSession("test")
        val provider = Provider()
        try {
            val handle = request(session, provider, "upload-start",
                WebUiStorageAdvancedRequest("connection-A", StoragePath("/file"), size = 1)).state().handle
            assertFails {
                advancedStoragePlatformRequest(LinkShareHttpRequest.from("PUT",
                    "$WEB_UI_PLATFORM_PATH/networks/advanced/upload-chunk?handle=$handle&offset=0",
                    body = LinkShareHttpRequestBody.Bytes(ByteArray(WEB_UI_FILE_STREAM_CHUNK_BYTES + 1))),
                    session, connection = { provider })
            }
            assertEquals(0, provider.offset)
            assertFails {
                advancedStoragePlatformRequest(LinkShareHttpRequest.from("GET",
                    "$WEB_UI_PLATFORM_PATH/networks/advanced/upload-finish"), session, connection = { provider })
            }
        } finally { session.close() }
    }
}
