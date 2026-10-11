package com.folderspan.service.webui

import kotlinx.serialization.Serializable

@Serializable
internal data class WebUiHttpClientOpen(val fingerprint: String? = null, val captureCertificate: Boolean = false)
@Serializable
internal data class WebUiHttpOutgoingRequest(
    val client: String, val url: String, val method: String,
    val headers: List<Pair<String, String>>, val contentLength: Long?,
)
@Serializable
internal data class WebUiHttpIncomingResponse(val status: Int, val headers: List<Pair<String, String>>, val fingerprint: String? = null)
