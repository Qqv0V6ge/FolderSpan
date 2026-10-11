package com.folderspan.service.webui

import kotlinx.serialization.Serializable

enum class WebUiMode {
    Native,
    Attached,
}

@Serializable
data class WebUiLoginRequest(
    val secret: String = "",
    val protocolVersion: Int = 0,
)

@Serializable
data class WebUiLoginResponse(
    val ok: Boolean = true,
    val sessionToken: String = "",
    val host: WebUiHostInfo? = null,
)

@Serializable
data class WebUiJsonError(
    val error: String,
    val code: String = "unauthorized",
)
