package com.folderspan.service.http.client

import com.folderspan.createSettings
import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.folderspan.service.http.readFileShareAccessKeyConfig
import com.russhwolf.settings.Settings
import io.ktor.client.request.*
import io.ktor.http.*

internal fun HttpRequestBuilder.applyFileShareAccessKey(
    settings: Settings = createSettings(),
) {
    headers.applyFileShareAccessKey(settings)
}

internal fun HeadersBuilder.applyFileShareAccessKey(
    settings: Settings = createSettings(),
) {
    val config = settings.readFileShareAccessKeyConfig()
    if (!config.enabled || !config.hasValidValue()) return
    remove(FILE_SHARE_ACCESS_KEY_HEADER)
    append(FILE_SHARE_ACCESS_KEY_HEADER, config.value)
}
