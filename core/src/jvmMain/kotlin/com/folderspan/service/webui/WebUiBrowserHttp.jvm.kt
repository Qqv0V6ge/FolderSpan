package com.folderspan.service.webui

actual object WebUiBrowserHttp {
    actual suspend fun exchange(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): WebUiHttpExchange = WebUiHttpExchange(status = 404)

    actual fun selectedTarget(): String? = null

    actual fun selectTargetAndReload(address: String) = Unit

    actual fun origin(): String = ""

    actual fun isPlainHttp(): Boolean = false

    actual suspend fun probeBootstrap(): Boolean = false
}
