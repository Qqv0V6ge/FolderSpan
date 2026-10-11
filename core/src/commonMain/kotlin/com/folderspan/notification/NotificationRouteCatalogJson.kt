package com.folderspan.notification

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class NotificationRouteCatalogExport(
    val version: Int,
    val routes: List<NotificationRouteExport>,
)

@Serializable
private data class NotificationRouteExport(
    val routeKey: String,
    val displayName: NotificationRouteDisplayNameExport,
    val group: String,
    val requiresAuth: Boolean,
    val params: List<NotificationRouteParameterExport>,
)

@Serializable
private data class NotificationRouteDisplayNameExport(
    @SerialName("zh-CN") val zhCn: String,
    @SerialName("en-US") val enUs: String,
)

@Serializable
private data class NotificationRouteParameterExport(
    val name: String,
    val type: String,
    val required: Boolean,
    val displayName: NotificationRouteDisplayNameExport,
    val options: List<String>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
private val notificationRouteCatalogJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
}

fun exportNotificationRouteCatalogJson(
    declarations: List<NotificationRouteDeclaration> = NotificationRouteCatalog.routes,
): String {
    val catalog = NotificationRouteCatalogExport(
        version = NotificationRouteCatalog.VERSION,
        routes = declarations.sortedBy(NotificationRouteDeclaration::routeKey).map { declaration ->
            NotificationRouteExport(
                routeKey = declaration.routeKey,
                displayName = declaration.displayName.toExport(),
                group = declaration.group,
                requiresAuth = declaration.requiresAuth,
                params = declaration.parameters.map { parameter ->
                    NotificationRouteParameterExport(
                        name = parameter.name,
                        type = parameter.type.wireName,
                        required = parameter.required,
                        displayName = parameter.displayName.toExport(),
                        options = parameter.options.takeIf { parameter.type == NotificationRouteParameterType.Enum },
                    )
                },
            )
        },
    )
    return notificationRouteCatalogJson.encodeToString(catalog) + "\n"
}

private fun NotificationRouteDisplayName.toExport(): NotificationRouteDisplayNameExport =
    NotificationRouteDisplayNameExport(zhCn = zhCn, enUs = enUs)
