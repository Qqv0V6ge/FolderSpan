package com.folderspan.service.network

import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiPlatformClient
import strings.AppStrings

actual object StorageAccountPlatform {
    private fun sessionKey() = WebUiAttachment.target?.httpUrl("/").orEmpty() + "\n" + WebUiAttachment.sessionToken
    private val sessions = mutableMapOf<String, String>()
    private fun checkSession(handle: String) {
        check(sessions[handle] == sessionKey()) { "Storage authorization belongs to another Runtime session" }
    }
    actual suspend fun accounts(): List<StorageAccount> = WebUiPlatformClient.get("/storage/accounts")
    actual suspend fun begin(request: StorageAuthorizationRequest): StorageAuthorizationStart {
        request.validatePermissions()
        OfficialStorageAuthorization.requireAvailable(request.protocol)
        val session = sessionKey()
        val result = WebUiPlatformClient.post<StorageAuthorizationRequest, StorageAuthorizationStart>("/storage/start", request)
        check(session == sessionKey()) { "Runtime session changed" }
        sessions[result.handle] = session
        if (!result.acceptedPermissions.containsAll(request.permissions)) {
            cancel(result.handle)
            error(AppStrings.storage_auth_permissions_unconfirmed)
        }
        return result
    }
    actual suspend fun poll(handle: String): StorageAuthorizationPoll {
        checkSession(handle)
        return WebUiPlatformClient.post<StorageAuthorizationHandle, StorageAuthorizationPoll>("/storage/poll", StorageAuthorizationHandle(handle)).also {
            if (it.status in setOf("authorized", "denied", "failed", "cancelled", "expired")) sessions.remove(handle)
        }
    }
    actual suspend fun cancel(handle: String) {
        if (sessions.remove(handle) != sessionKey()) return
        WebUiPlatformClient.post<StorageAuthorizationHandle, Boolean>("/storage/cancel", StorageAuthorizationHandle(handle))
    }
    actual suspend fun remove(id: String) { WebUiPlatformClient.post<StorageAccountHandle, Boolean>("/storage/remove", StorageAccountHandle(id)) }
    actual suspend fun installSeafile(request: SeafileAuthorizationRequest): StorageAccount = WebUiPlatformClient.post("/storage/seafile", request)
    actual suspend fun libraries(id: String): List<StorageLibrary> = WebUiPlatformClient.post("/storage/libraries", StorageAccountHandle(id))
}
