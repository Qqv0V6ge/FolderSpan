package com.folderspan.service.http.tls

import javax.net.ssl.SSLServerSocket

internal fun SSLServerSocket.restrictToModernTls() {
    val allowed = arrayOf("TLSv1.3", "TLSv1.2")
    enabledProtocols = allowed.filter { item -> item in supportedProtocols }.toTypedArray()
    check(enabledProtocols.isNotEmpty()) { "no modern TLS protocol is supported" }
}
