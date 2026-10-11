package com.folderspan.service.session

import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal fun SSLServerSocket.advertiseDeviceSessionAlpn() {
    val params = sslParameters
    params.applicationProtocols = arrayOf(DEVICE_SESSION_ALPN)
    sslParameters = params
}

internal fun SSLSocket.advertiseDeviceSessionAlpn() {
    configureDeviceSessionAlpn()
}

internal fun SSLSocket.offerDeviceSessionAlpn() {
    configureDeviceSessionAlpn()
}

private fun SSLSocket.configureDeviceSessionAlpn() {
    val params = sslParameters
    params.applicationProtocols = arrayOf(DEVICE_SESSION_ALPN)
    sslParameters = params
}

internal fun SSLSocket.hasDeviceSessionAlpn(): Boolean {
    return isDeviceSessionAlpn(applicationProtocol)
}
