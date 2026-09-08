package com.folderspan.pro.core.network

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.isEmpty
import io.ktor.util.generateNonceBlocking
import io.ktor.utils.io.InternalAPI
import kotlin.time.Clock

internal const val PRO_API_APP_KEY = "desktop-app"
internal const val PRO_API_APP_KEY_HEADER = "X-App-Key"
internal const val PRO_API_TIMESTAMP_HEADER = "X-Timestamp"
internal const val PRO_API_NONCE_HEADER = "X-Nonce"
internal const val PRO_API_SIGNATURE_HEADER = "X-Signature"

internal class RequestSigningPluginConfig {
    var appKey: String = PRO_API_APP_KEY
    var timestampProvider: () -> String = { Clock.System.now().epochSeconds.toString() }
    var nonceProvider: () -> String = { generateNonceBlocking() }
}

internal data class RequestSigningConfig(
    val appKey: String = PRO_API_APP_KEY,
    val timestampProvider: () -> String = { Clock.System.now().epochSeconds.toString() },
    val nonceProvider: () -> String = { generateNonceBlocking() },
)

internal fun requestSigningBearerToken(authorizationHeader: String?): String? {
    val raw = authorizationHeader?.trim().orEmpty()
    val schemeEnd = raw.indexOfFirst(Char::isWhitespace)
    if (schemeEnd <= 0 || !raw.substring(0, schemeEnd).equals("Bearer", ignoreCase = true)) {
        return null
    }
    return raw.substring(schemeEnd).trim().takeIf(String::isNotEmpty)
}

internal interface ReplayableRequestContent {
    fun replayableBodyBytes(): ByteArray
}

internal val RequestSigningPlugin = createClientPlugin(
    "RequestSigningPlugin",
    ::RequestSigningPluginConfig,
) {
    val appKey = pluginConfig.appKey
    val timestampProvider = pluginConfig.timestampProvider
    val nonceProvider = pluginConfig.nonceProvider

    on(SendingRequest) { request, content ->
        val requestAppKey = request.headers[PRO_API_APP_KEY_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: appKey
        request.setSignatureHeader(PRO_API_APP_KEY_HEADER, requestAppKey)

        val secret = requestSigningBearerToken(request.headers[HttpHeaders.Authorization])
            ?: return@on
        val timestamp = timestampProvider()
        val nonce = nonceProvider()
        val url = request.url.build()
        val signed = RequestSigner.sign(
            method = request.method.value,
            path = url.encodedPath.ifEmpty { "/" },
            query = url.parameters.toPairs(),
            body = content.replayableBodyBytes(),
            timestamp = timestamp,
            nonce = nonce,
            secret = secret,
        )

        request.setSignatureHeader(PRO_API_TIMESTAMP_HEADER, timestamp)
        request.setSignatureHeader(PRO_API_NONCE_HEADER, nonce)
        request.setSignatureHeader(PRO_API_SIGNATURE_HEADER, signed.signature)
    }
}

private fun HttpRequestBuilder.setSignatureHeader(name: String, value: String) {
    headers.remove(name)
    headers.append(name, value)
}

private fun Parameters.toPairs(): List<Pair<String, String>> =
    names().flatMap { key ->
        getAll(key).orEmpty().map { value -> key to value }
    }

@OptIn(InternalAPI::class)
private fun OutgoingContent.replayableBodyBytes(): ByteArray =
    when {
        isEmpty() -> ByteArray(0)
        this is ReplayableRequestContent -> replayableBodyBytes()
        this is OutgoingContent.ByteArrayContent -> bytes()
        this is OutgoingContent.ContentWrapper -> delegate().replayableBodyBytes()
        else -> error("Request signing only supports replayable request bodies: ${this::class}")
    }
