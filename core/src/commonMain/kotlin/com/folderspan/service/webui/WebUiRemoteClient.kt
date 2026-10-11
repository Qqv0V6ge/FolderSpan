package com.folderspan.service.webui

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Authentication and platform events only; all business state belongs to the caller. */
class WebUiRemoteClient(private val scope: CoroutineScope) {
    private val json = WebUiPlatformClient.json
    private var monitoring: Job? = null
    private var connection: WebUiManagementConnection? = null

    suspend fun login(secret: String): Result<Unit> = platformResult {
        logout()
        val target = checkNotNull(WebUiAttachment.target) { "请选择 Runtime 地址" }
        val bootstrap = WebUiBrowserHttp.exchange("GET", target.httpUrl(WEB_UI_BOOTSTRAP_PATH))
        check(bootstrap.status == 200) { "无法连接 Runtime，请检查地址和 TLS 证书" }
        val capabilities = json.parseToJsonElement(bootstrap.body.decodeToString()).jsonObject
        check(capabilities["protocolVersion"]?.jsonPrimitive?.intOrNull == WEB_UI_PROTOCOL_VERSION &&
            capabilities["transport"]?.jsonPrimitive?.content == "websocket") { "协议不兼容，请配套更新 Web 和 Runtime" }
        val response = WebUiBrowserHttp.exchange("POST", target.httpUrl(WEB_UI_LOGIN_PATH),
            body = json.encodeToString(WebUiLoginRequest(secret.trim(), WEB_UI_PROTOCOL_VERSION)).encodeToByteArray(), contentType = "application/json")
        check(response.status == 200) { "登录失败，请检查管理密钥" }
        val login = json.decodeFromString<WebUiLoginResponse>(response.body.decodeToString())
        val host = checkNotNull(login.host) { "Runtime 未提供平台信息" }
        check(login.sessionToken.isNotBlank())
        val socketUrl = target.httpUrl(WEB_UI_SOCKET_PATH).let {
            if (it.startsWith("https:")) "wss:" + it.removePrefix("https:") else "ws:" + it.removePrefix("http:")
        }
        val opened = try {
            WebUiManagementConnection.connect(socketUrl, login.sessionToken, scope, WebUiPlatformClient::enqueue)
        } catch (error: Throwable) {
            withContext(NonCancellable) { withTimeoutOrNull(2_000) { runCatching {
                WebUiBrowserHttp.exchange("POST", target.httpUrl(WEB_UI_LOGOUT_PATH), mapOf(
                    WEB_UI_SESSION_HEADER to login.sessionToken, WEB_UI_PROTOCOL_HEADER to WEB_UI_PROTOCOL_VERSION.toString()))
            } } }
            throw error
        }
        connection = opened
        WebUiPlatformClient.connection = opened
        WebUiAttachment.applyHostInfo(host)
        WebUiAttachment.sessionToken = login.sessionToken
        WebUiAttachment.stale = false
        WebUiAttachment.connected = true
        WebUiAttachment.hasSession = true
        monitoring = scope.launch {
            val failure = opened.closed.await()
            WebUiAttachment.stale = true
            WebUiPlatformClient.invalidate(failure)
            logout()
        }
    }

    suspend fun checkConnection() {
        // The management connection owns the lease and detects a disconnected Runtime.
        if (WebUiAttachment.hasSession && monitoring?.isActive != true) logout()
    }

    suspend fun disconnect() = logout()

    suspend fun logout() {
        if (monitoring != currentCoroutineContext()[Job]) monitoring?.cancelAndJoin()
        monitoring = null
        val previous = connection
        connection = null
        WebUiPlatformClient.connection = null
        previous?.close()
        val token = WebUiAttachment.sessionToken
        val target = WebUiAttachment.target
        WebUiAttachment.sessionToken = ""
        WebUiPlatformClient.invalidate(IllegalStateException("Runtime session closed"))
        if (token.isNotBlank() && target != null) withContext(NonCancellable) {
            withTimeoutOrNull(2_000) {
                runCatching { WebUiBrowserHttp.exchange("POST", target.httpUrl(WEB_UI_LOGOUT_PATH), mapOf(
                    WEB_UI_SESSION_HEADER to token, WEB_UI_PROTOCOL_HEADER to WEB_UI_PROTOCOL_VERSION.toString(),
                )) }
            }
        }
        WebUiAttachment.hasSession = false
        WebUiAttachment.connected = false
    }
}
