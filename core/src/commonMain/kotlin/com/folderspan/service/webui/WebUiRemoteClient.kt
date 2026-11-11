package com.folderspan.service.webui

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Authentication and platform events only; all business state belongs to the caller. */
class WebUiRemoteClient(private val scope: CoroutineScope) {
    private val json = WebUiPlatformClient.json
    private var monitoring: Job? = null
    private var connection: WebUiManagementConnection? = null
    private var generation = 0L

    private fun requireCurrent(attempt: Long, target: WebUiRuntimeAddress) {
        check(generation == attempt && WebUiAttachment.target === target) { "Runtime login context changed" }
    }

    private suspend fun retire(target: WebUiRuntimeAddress?, token: String, opened: WebUiManagementConnection? = null) =
        withContext(NonCancellable) {
            opened?.close()
            if (token.isNotBlank() && target != null) withTimeoutOrNull(2_000) { runCatching {
                WebUiBrowserHttp.exchange("POST", target.httpUrl(WEB_UI_LOGOUT_PATH), mapOf(
                    WEB_UI_SESSION_HEADER to token, WEB_UI_PROTOCOL_HEADER to WEB_UI_PROTOCOL_VERSION.toString()))
            } }
            Unit
        }

    suspend fun login(secret: String): Result<Unit> = platformResult {
        val target = checkNotNull(WebUiAttachment.target) { "请选择 Runtime 地址" }
        val attempt = ++generation
        clearSession()
        requireCurrent(attempt, target)
        val bootstrap = WebUiBrowserHttp.exchange("GET", target.httpUrl(WEB_UI_BOOTSTRAP_PATH))
        requireCurrent(attempt, target)
        check(bootstrap.status == 200) { "无法连接 Runtime，请检查地址和 TLS 证书" }
        val capabilities = json.parseToJsonElement(bootstrap.body.decodeToString()).jsonObject
        check(capabilities["protocolVersion"]?.jsonPrimitive?.intOrNull == WEB_UI_PROTOCOL_VERSION &&
            capabilities["transport"]?.jsonPrimitive?.content == "websocket") { "协议不兼容，请配套更新 Web 和 Runtime" }
        val response = WebUiBrowserHttp.exchange("POST", target.httpUrl(WEB_UI_LOGIN_PATH),
            body = json.encodeToString(WebUiLoginRequest(secret.trim(), WEB_UI_PROTOCOL_VERSION)).encodeToByteArray(), contentType = "application/json")
        check(response.status == 200) { "登录失败，请检查管理密钥" }
        val login = json.decodeFromString<WebUiLoginResponse>(response.body.decodeToString())
        check(login.sessionToken.isNotBlank())
        var opened: WebUiManagementConnection? = null
        try {
            requireCurrent(attempt, target)
            val host = checkNotNull(login.host) { "Runtime 未提供平台信息" }
            val socketUrl = target.httpUrl(WEB_UI_SOCKET_PATH).let {
                if (it.startsWith("https:")) "wss:" + it.removePrefix("https:") else "ws:" + it.removePrefix("http:")
            }
            val active = WebUiManagementConnection.connect(socketUrl, login.sessionToken, scope, WebUiPlatformClient::enqueue)
            opened = active
            requireCurrent(attempt, target)
            connection = active
            WebUiPlatformClient.connection = active
            WebUiAttachment.applyHostInfo(host)
            WebUiAttachment.sessionToken = login.sessionToken
            WebUiAttachment.stale = false
            WebUiAttachment.connected = true
            WebUiAttachment.hasSession = true
            monitoring = scope.launch {
                val failure = active.closed.await()
                if (generation == attempt && connection === active && WebUiAttachment.target === target) {
                    WebUiAttachment.stale = true
                    WebUiPlatformClient.invalidate(failure)
                    logout()
                }
            }
        } catch (error: Throwable) {
            retire(target, login.sessionToken, opened)
            throw error
        }
    }

    suspend fun checkConnection() {
        // The management connection owns the lease and detects a disconnected Runtime.
        if (WebUiAttachment.hasSession && monitoring?.isActive != true) logout()
    }

    suspend fun disconnect() = logout()

    suspend fun logout() {
        ++generation
        clearSession()
    }

    private suspend fun clearSession() {
        val previousMonitoring = monitoring
        val previous = connection
        val token = WebUiAttachment.sessionToken
        val target = WebUiAttachment.target
        monitoring = null
        connection = null
        WebUiPlatformClient.connection = null
        WebUiAttachment.sessionToken = ""
        WebUiAttachment.hasSession = false
        WebUiAttachment.connected = false
        // Clear shared state before any suspension so old cleanup cannot erase a newer login.
        val current = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            WebUiPlatformClient.invalidate(IllegalStateException("Runtime session closed"))
            if (previousMonitoring !== current) previousMonitoring?.cancelAndJoin()
            retire(target, token, previous)
        }
    }
}
