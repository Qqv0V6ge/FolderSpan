package com.folderspan.service.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.net.Proxy

internal actual fun createCloudStorageHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Java) {
        // Cloud metadata and media requests share the JDK TLS connection pool.
        engine {
            proxy = Proxy.NO_PROXY
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000L
            requestTimeoutMillis = 300_000L
        }
        config()
    }
