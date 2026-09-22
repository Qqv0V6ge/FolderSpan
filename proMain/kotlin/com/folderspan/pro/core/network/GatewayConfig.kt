package com.folderspan.pro.core.network

import com.folderspan.AppBuildConfig

private const val WEBRTC_PREFIX = "/api/v1/webrtc"
private const val USER_PREFIX = "/api/v1/user"
private const val PLUGIN_PREFIX = "/api/v1/plugins"
private const val PLUGIN_SIMPLE_PREFIX = "/api/v1/plugins/simple"
private const val ORDER_PREFIX = "/api/v1/orders"
private const val SETTINGS_PREFIX = "/api/v1/settings"
private const val FEEDBACK_PREFIX = "/api/v1/feedbacks"
private const val MESSAGE_PREFIX = "/api/v1/messages"
private const val UPDATES_PREFIX = "/api/v1/updates"

data class GatewayConfig(
    val baseUrl: String = AppBuildConfig.GATEWAY_BASE_URL,
    val webrtcPrefix: String = WEBRTC_PREFIX,
    val userPrefix: String = USER_PREFIX,
    val pluginPrefix: String = PLUGIN_PREFIX,
    val pluginSimplePrefix: String = PLUGIN_SIMPLE_PREFIX,
    val orderPrefix: String = ORDER_PREFIX,
    val settingsPrefix: String = SETTINGS_PREFIX,
    val feedbackPrefix: String = FEEDBACK_PREFIX,
    val messagePrefix: String = MESSAGE_PREFIX,
    val updatesPrefix: String = UPDATES_PREFIX,
)

class RouteBuilder(private val config: GatewayConfig) {
    private fun build(prefix: String, path: String): String {
        val normalizedBase = config.baseUrl.trimEnd('/')
        val normalizedPrefix = prefix.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val prefixSection = if (normalizedPrefix.isEmpty()) "" else if (normalizedPrefix.startsWith("/")) normalizedPrefix else "/$normalizedPrefix"
        return normalizedBase + prefixSection + normalizedPath
    }

    fun webrtc(path: String) = build(config.webrtcPrefix, path)
    fun user(path: String) = build(config.userPrefix, path)
    fun plugin(path: String) = build(config.pluginPrefix, path)
    fun pluginSimple(path: String = "") = build(config.pluginSimplePrefix, path)
    fun order(path: String) = build(config.orderPrefix, path)
    fun settings(path: String) = build(config.settingsPrefix, path)
    fun feedback(path: String = ""): String =
        if (path.isEmpty()) build(config.feedbackPrefix, "/").removeSuffix("/") else build(config.feedbackPrefix, path)
    fun message(path: String = ""): String =
        if (path.isEmpty()) build(config.messagePrefix, "/").removeSuffix("/") else build(config.messagePrefix, path)
    fun updates(path: String = ""): String =
        if (path.isEmpty()) build(config.updatesPrefix, "/").removeSuffix("/") else build(config.updatesPrefix, path)
}
