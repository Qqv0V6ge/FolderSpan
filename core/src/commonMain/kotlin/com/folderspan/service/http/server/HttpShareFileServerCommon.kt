package com.folderspan.service.http.server

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkSharePlatformFileResponder

/** Listener callbacks contain no business state; their owner runs routing and authorization. */
class PlatformHttpCallbacks(
    val handle: suspend (LinkShareHttpRequest) -> LinkShareHttpResponse,
    val clearSessions: suspend () -> Unit = {},
    val configurePorts: (Int, Int?) -> Unit = { _, _ -> },
    val running: (Boolean) -> Unit = {},
)

abstract class HttpShareFileServerCommon(protected val callbacks: PlatformHttpCallbacks) :
    HttpShareFileServerInterface, LinkSharePlatformFileResponder {
    protected fun configureLinkSharePorts(httpPort: Int, httpsPort: Int?) = callbacks.configurePorts(httpPort, httpsPort)
    protected suspend fun dispatchLinkShareRequest(request: LinkShareHttpRequest) = callbacks.handle(request)
}
