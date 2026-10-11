package com.folderspan.notification

import com.folderspan.openUrl
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.AccountNotificationPayloadKeys
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.screen.main.NotificationScreen
import com.folderspan.ui.state.main.MainState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun dispatchAccountNotificationAction(
    action: NotificationAction,
    registry: NotificationRouteRegistry = appNotificationRouteRegistry,
    openExternalUrl: (String) -> Unit,
    openScreen: (AppScreenRoute) -> Unit,
): Boolean = when (action.kind) {
    NotificationActionKind.Url -> {
        val url = action.url?.trim()?.takeIf(::isHttpUrl) ?: return false
        openExternalUrl(url)
        true
    }

    NotificationActionKind.Route -> {
        val routeKey = action.route?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val resolution = registry.resolve(routeKey, action.params) ?: return false
        openScreen(resolution.screen)
        true
    }
}

internal fun isAccountNotificationActionAvailable(
    action: NotificationAction,
    registry: NotificationRouteRegistry = appNotificationRouteRegistry,
): Boolean = when (action.kind) {
    NotificationActionKind.Url -> action.url?.trim()?.let(::isHttpUrl) == true
    NotificationActionKind.Route -> {
        val routeKey = action.route?.trim()?.takeIf(String::isNotEmpty) ?: return false
        registry.resolve(routeKey, action.params) != null
    }
}

internal fun dispatchPrimaryAccountNotificationAction(
    action: NotificationAction?,
    registry: NotificationRouteRegistry = appNotificationRouteRegistry,
    openExternalUrl: (String) -> Unit,
    openScreen: (AppScreenRoute) -> Unit,
    fallback: () -> Unit,
) {
    if (action == null || !dispatchAccountNotificationAction(action, registry, openExternalUrl, openScreen)) {
        fallback()
    }
}

internal fun primaryAccountNotificationActionFromPayload(
    data: NotificationPayload,
): NotificationAction? {
    val kind = NotificationActionKind.entries.firstOrNull { item ->
        item.wireValue.equals(data[AccountNotificationPayloadKeys.PrimaryActionKind], ignoreCase = true)
    } ?: return null
    val target = data[AccountNotificationPayloadKeys.PrimaryActionTarget]?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val params = when (kind) {
        NotificationActionKind.Url -> emptyMap()
        NotificationActionKind.Route -> {
            val serializedParams = data[AccountNotificationPayloadKeys.PrimaryActionParams]
            if (serializedParams.isNullOrBlank()) {
                emptyMap()
            } else {
                runCatching { Json.decodeFromString<Map<String, String>>(serializedParams) }
                    .getOrNull()
                    ?: return null
            }
        }
    }
    return NotificationAction(
        label = "",
        style = NotificationActionStyle.Primary,
        kind = kind,
        url = target.takeIf { kind == NotificationActionKind.Url },
        route = target.takeIf { kind == NotificationActionKind.Route },
        params = params,
    )
}

fun openAccountNotification(
    mainState: MainState,
    notification: AccountNotification? = null,
    action: NotificationAction? = notification?.primaryAction(),
) {
    dispatchPrimaryAccountNotificationAction(
        action = action,
        openExternalUrl = ::openUrl,
        openScreen = mainState::requestOpenScreen,
        fallback = { mainState.requestOpenScreen(NotificationScreen()) },
    )
}

internal fun performAccountNotificationAction(
    mainState: MainState,
    action: NotificationAction,
): Boolean = dispatchAccountNotificationAction(
    action = action,
    openExternalUrl = ::openUrl,
    openScreen = mainState::requestOpenScreen,
)

private fun AccountNotification.primaryAction(): NotificationAction? =
    actions.firstOrNull { action -> action.style == NotificationActionStyle.Primary }
        ?: actions.firstOrNull()

internal fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
