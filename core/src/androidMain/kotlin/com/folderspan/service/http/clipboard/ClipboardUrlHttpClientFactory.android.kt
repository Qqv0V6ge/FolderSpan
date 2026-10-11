package com.folderspan.service.http.clipboard

import com.folderspan.service.http.client.createNoProxyHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

actual val clipboardUrlDownloadPlatformCapabilities = ClipboardUrlDownloadPlatformCapabilities(
    platformName = "Android",
    canSetCookie = true,
    canSetUserAgent = true,
    canControlAutomaticHeaders = true,
    browserCredentialsOmitted = false,
)

internal actual fun createClipboardUrlPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = createNoProxyHttpClient(config)
