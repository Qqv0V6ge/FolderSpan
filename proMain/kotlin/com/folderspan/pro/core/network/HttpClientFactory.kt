package com.folderspan.pro.core.network

import com.folderspan.AppBuildConfig
import com.folderspan.localization.DefaultAppLanguagePlatform
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.serialization.json.Json

internal const val PRO_API_DEVICE_TYPE_HEADER = "X-Device-Type"
internal const val PRO_API_DEVICE_KEY_HEADER = "X-Device-Key"
internal const val PRO_API_DEVICE_NAME_HEADER = "X-Device-Name"
internal val PRO_API_HOST_HEADER_VALUE: String = AppBuildConfig.API_HOST_HEADER

fun httpClient(): HttpClient = configuredHttpClient(useProxy = true)

internal fun directHttpClient(): HttpClient = configuredHttpClient(useProxy = false)

internal fun attachmentDownloadHttpClient(): HttpClient = HttpClient()

private fun configuredHttpClient(useProxy: Boolean): HttpClient = HttpClient {
    installCommonConfig(useProxy = useProxy)
}

internal val defaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installCommonConfig(
    requestSigningConfig: RequestSigningConfig = RequestSigningConfig(),
    deviceIdentity: DeviceIdentity = runtimeDeviceIdentity(),
    deviceLanguageTagsProvider: () -> List<String> =
        DefaultAppLanguagePlatform::preferredLanguageTags,
    useProxy: Boolean = true,
) {
    if (useProxy) {
        runtimeHttpProxyConfig()
            ?.takeIf { isExplicitHttpProxySupported() }
            ?.let { proxyConfig ->
                engine {
                    proxy = ProxyBuilder.http(proxyConfig.url)
                }
            }
    }
    install(ContentNegotiation) {
        json(defaultJson)
    }
    install(WebSockets)
    install(SSE)
    install(RequestSigningPlugin) {
        appKey = requestSigningConfig.appKey
        timestampProvider = requestSigningConfig.timestampProvider
        nonceProvider = requestSigningConfig.nonceProvider
    }
    defaultRequest {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header(HttpHeaders.Accept, ContentType.Application.Json)
        deviceLanguageTagsProvider().toAcceptLanguageHeaderValue()?.let { languageTags ->
            header(HttpHeaders.AcceptLanguage, languageTags)
        }
        header(HttpHeaders.Host, PRO_API_HOST_HEADER_VALUE)
        header(PRO_API_DEVICE_TYPE_HEADER, deviceIdentity.type)
        header(PRO_API_DEVICE_KEY_HEADER, deviceIdentity.key)
        header(PRO_API_DEVICE_NAME_HEADER, deviceIdentity.name)
    }
}

private fun isExplicitHttpProxySupported(): Boolean =
    !PlatformUtils.IS_JS && !PlatformUtils.IS_WASM_JS

private fun List<String>.toAcceptLanguageHeaderValue(): String? =
    asSequence()
        .map { languageTag ->
            languageTag
                .trim()
                .substringBefore('@')
                .replace('_', '-')
        }
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(",")
        .takeIf(String::isNotEmpty)
