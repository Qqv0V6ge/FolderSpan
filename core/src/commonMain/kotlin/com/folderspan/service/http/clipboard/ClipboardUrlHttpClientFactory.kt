package com.folderspan.service.http.clipboard

import com.folderspan.utils.currentAppVersion
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import strings.AppStrings

expect val clipboardUrlDownloadPlatformCapabilities: ClipboardUrlDownloadPlatformCapabilities

internal expect fun createClipboardUrlPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient

fun defaultClipboardUrlUserAgent(
    version: String = currentAppVersion(),
    capabilities: ClipboardUrlDownloadPlatformCapabilities = clipboardUrlDownloadPlatformCapabilities,
): String = if (capabilities.canSetUserAgent) {
    "FolderSpan/$version (${capabilities.platformName}) ClipboardUrlDownload/1"
} else {
    AppStrings.ui_managed_by_browser
}

class ClipboardUrlHttpClientFactory(
    private val policy: ClipboardUrlDownloadPolicy = ClipboardUrlDownloadPolicy(),
) {
    fun create(): HttpClient = createClipboardUrlPlatformHttpClient {
        expectSuccess = false
        followRedirects = clipboardUrlDownloadPlatformCapabilities.isBrowser
        install(HttpTimeout) {
            connectTimeoutMillis = policy.connectTimeoutMillis
            socketTimeoutMillis = policy.idleReadTimeoutMillis
            requestTimeoutMillis = policy.totalTaskTimeoutMillis
        }
    }
}
