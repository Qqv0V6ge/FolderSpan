package com.folderspan.service.webui

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

actual object WebUiBrowserHttp {
    private val client = browserPlatformHttpClient()

    actual suspend fun exchange(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): WebUiHttpExchange = try {
        val response = client.request(path) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (name, value) -> header(name, value) }
            if (contentType != null) {
                contentType(ContentType.parse(contentType))
            }
            if (body != null) {
                setBody(body)
            }
        }
        WebUiHttpExchange(
            status = response.status.value,
            headers = response.headers.entries().associate { entry ->
                entry.key.lowercase() to (entry.value.firstOrNull().orEmpty())
            },
            body = response.bodyAsBytes(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // Ktor's Fetch bridge reports network failures as Kotlin Error on JS/Wasm.
        // Normalize at the transport boundary so shared Result/Exception handlers receive them.
        throw if (failure is Exception) failure else IllegalStateException("Runtime transport failed", failure)
    }

    actual fun selectedTarget(): String? = runCatching {
        window.sessionStorage.getItem("folderspan.webui.target")
    }.getOrNull()

    actual fun selectTargetAndReload(address: String) {
        window.sessionStorage.setItem("folderspan.webui.target", address)
        window.location.reload()
    }

    actual fun origin(): String = window.location.origin

    actual fun isPlainHttp(): Boolean =
        window.location.protocol.equals("http:", ignoreCase = true)

    actual suspend fun probeBootstrap(): Boolean {
        val response = runCatching {
            exchange(method = "GET", path = WEB_UI_BOOTSTRAP_PATH)
        }.getOrNull() ?: return false
        if (response.status != 200) return false
        return response.body.decodeToString().contains("\"mode\"") &&
            response.body.decodeToString().contains("attached")
    }
}

internal expect fun browserPlatformHttpClient(): HttpClient
