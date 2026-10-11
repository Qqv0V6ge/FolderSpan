package com.folderspan.runtime

import com.folderspan.createSettings
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.webui.*
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** The headless process owns native resources only. Business lifecycles stay in clients. */
suspend fun startHeadlessPlatformRuntime(
    settings: Settings = createSettings(),
    port: Int = settings.getInt(SettingsUtils.KEY_WEB_UI_PORT, DEFAULT_WEB_UI_PORT),
    onStarted: (FolderSpanRuntimeStatus) -> Unit = {},
) {
    require(port in 1..65535)
    val securityPolicy = WebUiSecurityPolicy(
        trustedOrigins = System.getenv("FOLDERSPAN_WEB_UI_ALLOWED_ORIGINS").orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
        advertisedHostProvider = { webUiAdvertisedHosts(getAllIPAddresses(SocketClientIPEnum.ALL)).toSet() },
    )
    val credentials = WebUiCredentialStore(settings)
    val setup = credentials.ensureInitialized()
    val handler = WebUiRequestHandler(
        credentials = credentials,
        sessions = WebUiSessionStore(),
        securityPolicy = securityPolicy,
        documentRoot = createWebUiDocumentRoot(),
    )
    val service = WebUiHttpService(handler)
    try {
        val status = service.start(port)
        check(status.running) { "Web UI startup failed: ${status.errorCode}" }
        printWebUiUrls(status.endpoints.map { it.url }, setup.oneTimeSecret)
        onStarted(FolderSpanRuntimeStatus(lockHeld = true, webUiRunning = true, webUiPort = port))
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { service.stop() }
    }
}
