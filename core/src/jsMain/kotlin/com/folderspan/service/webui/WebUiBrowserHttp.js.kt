package com.folderspan.service.webui

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

internal actual fun browserPlatformHttpClient() = HttpClient(Js) {
        expectSuccess = false
        followRedirects = false
        engine {
            configureRequest {
                redirect = "error"
                credentials = "omit"
            }
        }
    }
