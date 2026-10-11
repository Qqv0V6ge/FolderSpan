package com.folderspan.service.webui

data class WebUiHttpServiceStatus(
    val supported: Boolean,
    val running: Boolean,
    val port: Int? = null,
    val endpoints: List<WebUiAdvertisedEndpoint> = emptyList(),
    val tlsFingerprintSha256: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val documentRootReady: Boolean = false,
)

interface WebUiHttpServiceInterface {
    suspend fun start(port: Int = DEFAULT_WEB_UI_PORT): WebUiHttpServiceStatus
    suspend fun stop(): WebUiHttpServiceStatus
    fun status(): WebUiHttpServiceStatus
}

expect class WebUiHttpService(handler: WebUiRequestHandler) : WebUiHttpServiceInterface {
    override suspend fun start(port: Int): WebUiHttpServiceStatus
    override suspend fun stop(): WebUiHttpServiceStatus
    override fun status(): WebUiHttpServiceStatus
}
