package com.folderspan.service.http.server

import com.folderspan.service.http.server.raw.RawTlsHttpServer
import com.folderspan.service.session.DeviceListenerCallbacks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal actual suspend fun runDeviceListener(port: Int, callbacks: DeviceListenerCallbacks) {
    val server = RawTlsHttpServer(callbacks)
    try { server.start(port); awaitCancellation() }
    finally { withContext(NonCancellable) { server.stop() } }
}
