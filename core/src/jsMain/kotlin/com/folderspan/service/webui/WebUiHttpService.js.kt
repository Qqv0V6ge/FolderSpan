package com.folderspan.service.webui

actual class WebUiHttpService actual constructor(
    @Suppress("unused") handler: WebUiRequestHandler,
) : WebUiHttpServiceInterface {
    private val unsupported = WebUiHttpServiceStatus(
        supported = false,
        running = false,
        errorCode = "unsupported",
        errorMessage = "Web UI listener is not supported on JS",
    )

    actual override suspend fun start(port: Int): WebUiHttpServiceStatus = unsupported
    actual override suspend fun stop(): WebUiHttpServiceStatus = unsupported
    actual override fun status(): WebUiHttpServiceStatus = unsupported
}
