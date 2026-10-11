package com.folderspan.service.http.client

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import io.ktor.http.*

fun SocketDevice.httpsPortOrFallback(): Int {
    return httpsPort.takeIf { item -> item > 0 } ?: port
}

fun SocketDevice.httpPortOrFallback(): Int {
    return port.takeIf { item -> item > 0 } ?: httpsPortOrFallback()
}

fun SocketDevice.httpsBaseUrl(): String {
    return "https://${hostForUrl(host)}:${httpsPortOrFallback()}"
}

fun SocketDevice.httpBaseUrl(): String {
    return "http://${hostForUrl(host)}:${httpPortOrFallback()}"
}

fun usePlainHttpDeviceTransport(): Boolean {
    return false
}

fun useBrowserHttpDeviceTransport(): Boolean {
    return PlatformType == DeviceType.JS
}

fun SocketDevice.deviceApiBaseUrl(): String {
    return if (useBrowserHttpDeviceTransport()) httpBaseUrl() else httpsBaseUrl()
}

fun SocketDevice.deviceShareApprovalBaseUrl(): String {
    val approvalPort = port
    require(approvalPort in 1..65535) { "Device share approval port must be valid" }
    if (transportType == DeviceTransportType.Session) {
        require(httpsPort in 1..65535) { "Device Session port must be valid" }
        require(approvalPort != httpsPort) { "Device share approval port must differ from Session port" }
    }
    val scheme = if (useBrowserHttpDeviceTransport()) "http" else "https"
    return "$scheme://${hostForUrl(host)}:$approvalPort"
}

fun deviceApiProtocol(
    browserHttpTransport: Boolean = useBrowserHttpDeviceTransport(),
    plainHttpTransport: Boolean = usePlainHttpDeviceTransport(),
): URLProtocol {
    return if (browserHttpTransport || plainHttpTransport) URLProtocol.HTTP else URLProtocol.HTTPS
}

fun SocketDevice.normalizedTlsFingerprint(): String {
    return normalizeTlsFingerprintSha256(tlsFingerprintSha256)
}

private fun hostForUrl(value: String): String {
    val host = value.trim().removePrefix("[").removeSuffix("]")
    return if (host.contains(":")) "[$host]" else host
}
