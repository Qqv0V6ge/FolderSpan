package com.folderspan.service.http.clipboard

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

actual val clipboardUrlDownloadPlatformCapabilities = ClipboardUrlDownloadPlatformCapabilities(
    platformName = "Web",
    canSetCookie = false,
    canSetUserAgent = false,
    canControlAutomaticHeaders = false,
    browserCredentialsOmitted = true,
)

@OptIn(ExperimentalWasmJsInterop::class)
internal actual fun createClipboardUrlPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Js) {
    engine {
        configureRequest {
            credentials = "omit".toJsString()
        }
    }
    @Suppress("UNCHECKED_CAST")
    this.config()
}
