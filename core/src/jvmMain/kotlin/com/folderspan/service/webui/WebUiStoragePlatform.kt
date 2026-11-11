package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.network.*
import kotlinx.serialization.SerializationException

internal class StorageAuthorizationResource(val poll: suspend () -> StorageAuthorizationPoll, val close: suspend () -> Unit)

internal suspend fun storagePlatformRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    val operation = request.path.removePrefix(WEB_UI_PLATFORM_PATH + "/storage")
    suspend fun resource(handle: String): StorageAuthorizationResource =
        session.resource(handle) as? StorageAuthorizationResource ?: error("Not a storage authorization handle")
    return try {
        when (request.method to operation) {
            "GET" to "/accounts" -> platformResponse(StorageAccountPlatform.accounts())
            "POST" to "/start" -> {
                val start = StorageAccountPlatform.begin(request.platformBody<StorageAuthorizationRequest>())
                val resource = StorageAuthorizationResource({ StorageAccountPlatform.poll(start.handle) }, { StorageAccountPlatform.cancel(start.handle) })
                val handle = session.own(resource, resource.close)
                platformResponse(start.copy(handle = handle))
            }
            "POST" to "/poll" -> {
                val handle = request.platformBody<StorageAuthorizationHandle>().handle
                val result = resource(handle).poll()
                if (result.status in setOf("authorized", "denied", "failed", "cancelled", "expired")) session.release(handle)
                platformResponse(result)
            }
            "POST" to "/cancel" -> {
                val handle = request.platformBody<StorageAuthorizationHandle>().handle
                resource(handle)
                session.release(handle)
                platformResponse(true)
            }
            "POST" to "/seafile" -> platformResponse(StorageAccountPlatform.installSeafile(request.platformBody<SeafileAuthorizationRequest>()))
            "POST" to "/libraries" -> platformResponse(StorageAccountPlatform.libraries(request.platformBody<StorageAccountHandle>().id))
            "POST" to "/remove" -> {
                StorageAccountPlatform.remove(request.platformBody<StorageAccountHandle>().id)
                platformResponse(true)
            }
            else -> error("Unknown storage authorization operation")
        }
    } catch (_: SerializationException) {
        error("Invalid storage authorization request")
    }
}
