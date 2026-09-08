package com.folderspan.notification

import strings.AppStrings

import com.folderspan.ui.navigation.AppScreenRoute

data class NotificationRouteDisplayName(
    val zhCn: String,
    val enUs: String,
)

enum class NotificationRouteParameterType(val wireName: String) {
    String("string"),
    Long("long"),
    Boolean("boolean"),
    Enum("enum"),
}

sealed interface NotificationRouteParameterValue {
    data class StringValue(val value: String) : NotificationRouteParameterValue

    data class LongValue(val value: Long) : NotificationRouteParameterValue

    data class BooleanValue(val value: Boolean) : NotificationRouteParameterValue

    data class EnumValue(val value: String) : NotificationRouteParameterValue
}

data class NotificationRouteParameterDeclaration(
    val name: String,
    val type: NotificationRouteParameterType,
    val required: Boolean,
    val displayName: NotificationRouteDisplayName,
    val options: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Notification route parameter name must not be blank." }
        require(type == NotificationRouteParameterType.Enum || options.isEmpty()) {
            "Only enum notification route parameters may declare options."
        }
        require(type != NotificationRouteParameterType.Enum || options.isNotEmpty()) {
            "Enum notification route parameter '$name' must declare at least one option."
        }
        require(options.distinct().size == options.size) {
            "Notification route parameter '$name' contains duplicate enum options."
        }
    }

    internal fun parse(value: String): NotificationRouteParameterValue? = when (type) {
        NotificationRouteParameterType.String -> NotificationRouteParameterValue.StringValue(value)
        NotificationRouteParameterType.Long -> value.toLongOrNull()
            ?.let(NotificationRouteParameterValue::LongValue)

        NotificationRouteParameterType.Boolean -> when (value) {
            "true" -> NotificationRouteParameterValue.BooleanValue(true)
            "false" -> NotificationRouteParameterValue.BooleanValue(false)
            else -> null
        }

        NotificationRouteParameterType.Enum -> value.takeIf(options::contains)
            ?.let(NotificationRouteParameterValue::EnumValue)
    }
}

interface NotificationRouteScreenFactory {
    fun feedbackTickets(ticketUuid: String?): AppScreenRoute

    fun notificationCenter(): AppScreenRoute

    fun userProfile(): AppScreenRoute

    fun personalSettings(): AppScreenRoute

    fun permissionSettings(): AppScreenRoute

    fun settings(): AppScreenRoute
}

data class NotificationRouteDeclaration(
    val routeKey: String,
    val displayName: NotificationRouteDisplayName,
    val group: String,
    val requiresAuth: Boolean,
    val parameters: List<NotificationRouteParameterDeclaration>,
    val screenFactory: (
        NotificationRouteScreenFactory,
        Map<String, NotificationRouteParameterValue>,
    ) -> AppScreenRoute,
) {
    init {
        require(ROUTE_KEY_PATTERN.matches(routeKey)) {
            "Notification routeKey '$routeKey' must use snake_case."
        }
        require(group.isNotBlank()) { "Notification route '$routeKey' must declare a group." }
        val duplicateParameters = parameters.groupingBy(NotificationRouteParameterDeclaration::name)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateParameters.isEmpty()) {
            "Notification route '$routeKey' contains duplicate parameters: " +
                duplicateParameters.sorted().joinToString()
        }
    }

    private companion object {
        val ROUTE_KEY_PATTERN = Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")
    }
}

data class ResolvedNotificationRoute(
    val routeKey: String,
    val requiresAuth: Boolean,
    val screen: AppScreenRoute,
)

class NotificationRouteRegistry(
    private val screenFactory: NotificationRouteScreenFactory,
    val declarations: List<NotificationRouteDeclaration> = NotificationRouteCatalog.routes,
) {
    private val declarationsByKey: Map<String, NotificationRouteDeclaration>

    init {
        val duplicateRouteKeys = declarations.groupingBy(NotificationRouteDeclaration::routeKey)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateRouteKeys.isEmpty()) {
            "Duplicate notification routeKey values: ${duplicateRouteKeys.sorted().joinToString()}"
        }
        declarationsByKey = declarations.associateBy(NotificationRouteDeclaration::routeKey)
    }

    fun resolve(
        routeKey: String,
        params: Map<String, String>,
    ): ResolvedNotificationRoute? {
        val declaration = declarationsByKey[routeKey] ?: return null
        val parametersByName = declaration.parameters.associateBy(NotificationRouteParameterDeclaration::name)
        if (params.keys.any { name -> name !in parametersByName }) return null
        if (declaration.parameters.any { parameter -> parameter.required && parameter.name !in params }) return null

        val parsedParams = mutableMapOf<String, NotificationRouteParameterValue>()
        for ((name, rawValue) in params) {
            val parameter = parametersByName.getValue(name)
            val parsedValue = parameter.parse(rawValue) ?: return null
            parsedParams[name] = parsedValue
        }

        return ResolvedNotificationRoute(
            routeKey = declaration.routeKey,
            requiresAuth = declaration.requiresAuth,
            screen = declaration.screenFactory(screenFactory, parsedParams),
        )
    }
}

object NotificationRouteCatalog {
    const val VERSION: Int = 1

    val routes: List<NotificationRouteDeclaration> = listOf(
        NotificationRouteDeclaration(
            routeKey = "feedback_tickets",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_task_details, enUs = "Ticket details"),
            group = "account",
            requiresAuth = true,
            parameters = listOf(
                NotificationRouteParameterDeclaration(
                    name = "ticketUuid",
                    type = NotificationRouteParameterType.String,
                    required = false,
                    displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_work_order_uuid, enUs = "Ticket UUID"),
                ),
            ),
            screenFactory = { factory, params ->
                factory.feedbackTickets(
                    (params["ticketUuid"] as? NotificationRouteParameterValue.StringValue)?.value,
                )
            },
        ),
        NotificationRouteDeclaration(
            routeKey = "notification_center",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_notification_center, enUs = "Notification center"),
            group = "account",
            requiresAuth = false,
            parameters = emptyList(),
            screenFactory = { factory, _ -> factory.notificationCenter() },
        ),
        NotificationRouteDeclaration(
            routeKey = "personal_settings",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_personal_settings, enUs = "Personal settings"),
            group = "account",
            requiresAuth = true,
            parameters = emptyList(),
            screenFactory = { factory, _ -> factory.personalSettings() },
        ),
        NotificationRouteDeclaration(
            routeKey = "permission_settings",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_permission_settings, enUs = "Permission settings"),
            group = "settings",
            requiresAuth = false,
            parameters = emptyList(),
            screenFactory = { factory, _ -> factory.permissionSettings() },
        ),
        NotificationRouteDeclaration(
            routeKey = "settings",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.settings_title, enUs = "Settings"),
            group = "settings",
            requiresAuth = false,
            parameters = emptyList(),
            screenFactory = { factory, _ -> factory.settings() },
        ),
        NotificationRouteDeclaration(
            routeKey = "user_profile",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_user_data, enUs = "User profile"),
            group = "account",
            requiresAuth = true,
            parameters = emptyList(),
            screenFactory = { factory, _ -> factory.userProfile() },
        ),
    )

    // Screens whose constructors accept local database identifiers or filesystem paths are intentionally absent.
    // For example, FileFilterManagerScreen and NetworkEditScreen must not accept server-authored values.
}
