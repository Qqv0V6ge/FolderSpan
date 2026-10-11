@file:OptIn(ExperimentalWasmJsInterop::class)
package com.folderspan.service.webui

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlin.js.ExperimentalWasmJsInterop

internal actual fun browserPlatformHttpClient() = HttpClient(Js) {
        expectSuccess = false
        followRedirects = false
        engine {
            configureRequest {
                redirect = "error".toJsString()
                credentials = "omit".toJsString()
            }
        }
    }
