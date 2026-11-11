package com.folderspan.service.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal expect fun createCloudStorageHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient
