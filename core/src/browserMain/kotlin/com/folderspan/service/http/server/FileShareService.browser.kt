package com.folderspan.service.http.server

import com.folderspan.service.session.*
import com.folderspan.service.webui.BrowserHttpListener
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal actual suspend fun runDeviceListener(port: Int, callbacks: DeviceListenerCallbacks) {
    val listener = BrowserHttpListener(PlatformHttpCallbacks(handle = { request ->
        if (request.path == "/api/share/heartbeat" && request.scheme == "https") callbacks.approval(request)
        else callbacks.http(request.asRawHttpRequest()).asLinkShareResponse()
    }), onDevice = { accepted, scope ->
        val channel = BrowserDeviceChannel(accepted.handle)
        try { callbacks.accept(channel, accepted.remoteHost, scope) } finally { channel.close() }
    })
    try { listener.start("device", port); awaitCancellation() }
    finally { withContext(NonCancellable) { listener.stop() } }
}
