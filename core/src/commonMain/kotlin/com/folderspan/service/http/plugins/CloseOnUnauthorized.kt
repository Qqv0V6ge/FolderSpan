package com.folderspan.service.http.plugins

import com.folderspan.utils.LogKit
import io.ktor.client.plugins.api.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import strings.AppStrings

/**
 * CloseOnUnauthorized 插件（客户端）
 *
 * 作用
 * - 拦截 HTTP 客户端响应；当状态码为 401(Unauthorized) 时：
 *   1) 先执行 onUnauthorized 回调（例如关闭 HttpRouteClientManager）；
 *   2) 抛出 CancellationException 终止本次请求处理。
 */
class CloseOnUnauthorizedConfig {
    var onUnauthorized: (suspend () -> Unit)? = null
}

val CloseOnUnauthorized = createClientPlugin(
    name = "CloseOnUnauthorized",
    createConfiguration = ::CloseOnUnauthorizedConfig
) {
    val onUnauthorized = pluginConfig.onUnauthorized

    onResponse { response ->
        if (response.status == HttpStatusCode.Unauthorized) {
            LogKit.w(AppStrings.ui_received_401_response_triggering_onunauthorized_and_terminating_the_request)
            onUnauthorized?.invoke()
            throw CancellationException(AppStrings.ui_401_unauthorized_request_terminated)
        }
    }
}
