package com.folderspan.service.network

import com.folderspan.service.http.client.createNoProxyHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal actual fun createCloudStorageHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    createNoProxyHttpClient(config)
