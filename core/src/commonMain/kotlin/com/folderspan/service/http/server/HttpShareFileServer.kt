@file:kotlin.jvm.JvmName("SharedHttpShareFileServer")

package com.folderspan.service.http.server

import com.folderspan.service.http.server.linkshare.LinkSharePlatformFileResponder
import com.folderspan.service.http.server.linkshare.LinkShareRouteDispatcher
import com.folderspan.ui.state.file.FileShareState

interface HttpShareFileServerInterface {
    suspend fun start(port: Int)
    suspend fun stop()
    fun isRunning(): Boolean
}

expect class HttpShareFileServer(callbacks: PlatformHttpCallbacks) : HttpShareFileServerInterface, LinkSharePlatformFileResponder {
    override suspend fun start(port: Int)
    override suspend fun stop()
    override fun isRunning(): Boolean
}

internal fun createHttpShareFileServer(state: FileShareState): HttpShareFileServer {
    lateinit var dispatcher: LinkShareRouteDispatcher
    val server = HttpShareFileServer(PlatformHttpCallbacks(
        handle = { dispatcher.dispatch(it) },
        configurePorts = { http, https -> dispatcher.configurePorts(http, https) },
        running = state::updateHttpServerRunning,
    ))
    dispatcher = LinkShareRouteDispatcher(state, server)
    return server
}
