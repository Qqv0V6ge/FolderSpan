package com.folderspan.service.http.server

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkSharePlatformFileResponder
import com.folderspan.service.http.server.linkshare.LinkShareRouteDispatcher
import com.folderspan.ui.state.file.FileShareState
import org.koin.core.component.KoinComponent

/**
 * Platform-independent link-share server base.
 *
 * Platform source sets own listener lifecycle and local-file streaming; shared code owns
 * browser/API route behavior through [LinkShareRouteDispatcher].
 */
abstract class HttpShareFileServerCommon(
    protected val fileShareState: FileShareState,
) : HttpShareFileServerInterface,
    LinkSharePlatformFileResponder,
    KoinComponent {
    private val dispatcher = LinkShareRouteDispatcher(fileShareState, this)

    protected fun configureLinkSharePorts(httpPort: Int, httpsPort: Int?) {
        dispatcher.configurePorts(httpPort, httpsPort)
    }

    protected suspend fun dispatchLinkShareRequest(request: LinkShareHttpRequest): LinkShareHttpResponse {
        return dispatcher.dispatch(request)
    }
}
