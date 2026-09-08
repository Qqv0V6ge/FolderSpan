package com.folderspan.service.session

import android.os.Build
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal fun SSLServerSocket.advertiseDeviceSessionAlpn() {
    applyDeviceSessionAlpn(sslParameters)?.let { params -> sslParameters = params }
}

internal fun SSLSocket.advertiseDeviceSessionAlpn() {
    configureDeviceSessionAlpn()
}

internal fun SSLSocket.offerDeviceSessionAlpn() {
    configureDeviceSessionAlpn()
}

internal fun SSLSocket.hasDeviceSessionAlpn(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return applicationProtocolViaReflection() == DEVICE_SESSION_ALPN
    }
    return isDeviceSessionAlpn(applicationProtocol)
}

private fun SSLSocket.configureDeviceSessionAlpn() {
    val configuredParameters = applyDeviceSessionAlpn(sslParameters)
    if (configuredParameters != null) {
        sslParameters = configuredParameters
        return
    }
    val configured = runCatching {
        val method = javaClass.getMethod("setAlpnProtocols", ByteArray::class.java)
        method.invoke(this, DEVICE_SESSION_ALPN_WIRE_BYTES)
    }.isSuccess
    check(configured) { "Android TLS provider does not support ALPN $DEVICE_SESSION_ALPN" }
}

private fun applyDeviceSessionAlpn(params: SSLParameters): SSLParameters? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        params.applicationProtocols = arrayOf(DEVICE_SESSION_ALPN)
        return params
    }
    return runCatching {
        val method = params.javaClass.getMethod("setApplicationProtocols", Array<String>::class.java)
        method.invoke(params, arrayOf(DEVICE_SESSION_ALPN))
        params
    }.getOrNull()
}

private fun SSLSocket.applicationProtocolViaReflection(): String? {
    val standardProtocol = runCatching {
        javaClass.getMethod("getApplicationProtocol").invoke(this) as? String
    }.getOrNull().orEmpty()
    if (standardProtocol.isNotEmpty()) return standardProtocol
    return runCatching {
        val selected = javaClass.getMethod("getAlpnSelectedProtocol").invoke(this) as? ByteArray
        selected?.decodeToString()
    }.getOrNull()
}
