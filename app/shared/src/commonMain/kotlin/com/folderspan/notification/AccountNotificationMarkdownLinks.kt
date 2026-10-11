package com.folderspan.notification

import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle

internal fun notificationLinkAction(
    label: String,
    target: String,
): NotificationAction? {
    if (label.isEmpty() || target.isEmpty()) return null
    if (target.any { character -> character.isWhitespace() || character.isISOControl() }) return null
    if (isHttpUrl(target)) {
        return NotificationAction(
            label = label,
            style = NotificationActionStyle.Secondary,
            kind = NotificationActionKind.Url,
            url = target,
        )
    }
    if (!target.startsWith(ROUTE_TARGET_PREFIX) || '#' in target) return null

    val routeTarget = target.removePrefix(ROUTE_TARGET_PREFIX)
    val querySeparator = routeTarget.indexOf('?')
    if (querySeparator >= 0 && routeTarget.indexOf('?', querySeparator + 1) >= 0) return null
    val routeKey = if (querySeparator < 0) routeTarget else routeTarget.substring(0, querySeparator)
    if (routeKey.isEmpty()) return null
    val params = if (querySeparator < 0) {
        emptyMap()
    } else {
        parseRouteQuery(routeTarget.substring(querySeparator + 1)) ?: return null
    }
    return NotificationAction(
        label = label,
        style = NotificationActionStyle.Secondary,
        kind = NotificationActionKind.Route,
        route = routeKey,
        params = params,
    )
}

private fun parseRouteQuery(query: String): Map<String, String>? {
    if (query.isEmpty()) return null
    val params = linkedMapOf<String, String>()
    for (component in query.split('&')) {
        val valueSeparator = component.indexOf('=')
        if (valueSeparator < 0) return null
        val name = percentDecodeUtf8(component.substring(0, valueSeparator)) ?: return null
        val value = percentDecodeUtf8(component.substring(valueSeparator + 1)) ?: return null
        if (name.isEmpty() || params.put(name, value) != null) return null
    }
    return params
}

private fun percentDecodeUtf8(value: String): String? = buildString {
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            append(value[index])
            index += 1
            continue
        }

        val bytes = mutableListOf<Byte>()
        while (index < value.length && value[index] == '%') {
            if (index + 2 >= value.length) return null
            val high = value[index + 1].hexDigitValueOrNull() ?: return null
            val low = value[index + 2].hexDigitValueOrNull() ?: return null
            bytes += ((high shl 4) or low).toByte()
            index += 3
        }
        val decoded = runCatching {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return null
        append(decoded)
    }
}

private fun Char.hexDigitValueOrNull(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}

private const val ROUTE_TARGET_PREFIX = "route:"
