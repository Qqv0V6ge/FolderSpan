package com.folderspan.notification

import strings.AppStrings

import androidx.compose.runtime.Composable
import com.folderspan.ui.navigation.AppScreenRoute
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NotificationRouteRegistryTest {
    private val screenFactory = RecordingScreenFactory()
    private val registry = NotificationRouteRegistry(screenFactory)

    @Test
    fun resolvesRegisteredRouteWithParameters() {
        val resolution = registry.resolve(
            routeKey = "feedback_tickets",
            params = mapOf("ticketUuid" to "ticket-123"),
        )

        assertEquals("feedback:ticket-123", resolution?.screen?.routeKey)
        assertEquals(true, resolution?.requiresAuth)
    }

    @Test
    fun resolvesRegisteredRouteWithoutParameters() {
        val resolution = registry.resolve("notification_center", emptyMap())

        assertEquals("notification-center", resolution?.screen?.routeKey)
        assertEquals(false, resolution?.requiresAuth)
    }

    @Test
    fun rejectsUnknownRouteKey() {
        assertNull(registry.resolve("missing_route", emptyMap()))
    }

    @Test
    fun rejectsMissingRequiredParameter() {
        val registry = NotificationRouteRegistry(
            screenFactory = screenFactory,
            declarations = listOf(typedRoute(required = true)),
        )

        assertNull(registry.resolve("typed_route", emptyMap()))
    }

    @Test
    fun rejectsUndeclaredParameter() {
        assertNull(
            registry.resolve(
                routeKey = "notification_center",
                params = mapOf("unexpected" to "value"),
            ),
        )
    }

    @Test
    fun rejectsValueWithWrongDeclaredType() {
        val registry = NotificationRouteRegistry(
            screenFactory = screenFactory,
            declarations = listOf(typedRoute(required = false)),
        )

        assertNull(
            registry.resolve(
                routeKey = "typed_route",
                params = mapOf(
                    "count" to "not-a-long",
                    "enabled" to "true",
                    "mode" to "compact",
                ),
            ),
        )
    }

    @Test
    fun parsesStringLongBooleanAndEnumParameters() {
        val registry = NotificationRouteRegistry(
            screenFactory = screenFactory,
            declarations = listOf(typedRoute(required = true)),
        )

        val resolution = registry.resolve(
            routeKey = "typed_route",
            params = mapOf(
                "name" to "sample",
                "count" to "42",
                "enabled" to "false",
                "mode" to "expanded",
            ),
        )

        assertEquals("typed:sample:42:false:expanded", resolution?.screen?.routeKey)
    }

    @Test
    fun detectsDuplicateRouteKeys() {
        val declaration = typedRoute(required = false)

        assertFailsWith<IllegalArgumentException> {
            NotificationRouteRegistry(
                screenFactory = screenFactory,
                declarations = listOf(declaration, declaration.copy()),
            )
        }
    }

    @Test
    fun catalogExportIsVersionedSortedAndByteStable() {
        val declarations = NotificationRouteCatalog.routes.reversed()

        val first = exportNotificationRouteCatalogJson(declarations)
        val second = exportNotificationRouteCatalogJson(declarations)
        val routeKeys = Regex("\\\"routeKey\\\": \\\"([^\\\"]+)\\\"")
            .findAll(first)
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(first, second)
        assertContains(first, "\"version\": 1")
        assertEquals(routeKeys.sorted(), routeKeys)
        assertFalse("timestamp" in first)
        assertFalse("buildId" in first)
    }

    private fun typedRoute(required: Boolean): NotificationRouteDeclaration =
        NotificationRouteDeclaration(
            routeKey = "typed_route",
            displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_test_notification_route_registry_type_routing, enUs = "Typed route"),
            group = "test",
            requiresAuth = false,
            parameters = listOf(
                NotificationRouteParameterDeclaration(
                    name = "name",
                    type = NotificationRouteParameterType.String,
                    required = required,
                    displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_name, enUs = "Name"),
                ),
                NotificationRouteParameterDeclaration(
                    name = "count",
                    type = NotificationRouteParameterType.Long,
                    required = required,
                    displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_test_notification_route_registry_quantity, enUs = "Count"),
                ),
                NotificationRouteParameterDeclaration(
                    name = "enabled",
                    type = NotificationRouteParameterType.Boolean,
                    required = required,
                    displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_test_notification_route_registry_enable, enUs = "Enabled"),
                ),
                NotificationRouteParameterDeclaration(
                    name = "mode",
                    type = NotificationRouteParameterType.Enum,
                    required = required,
                    displayName = NotificationRouteDisplayName(zhCn = AppStrings.ui_test_notification_route_registry_pattern, enUs = "Mode"),
                    options = listOf("compact", "expanded"),
                ),
            ),
            screenFactory = { factory, params ->
                TestScreen(
                    routeKey = "typed:" + listOf(
                        (params["name"] as? NotificationRouteParameterValue.StringValue)?.value,
                        (params["count"] as? NotificationRouteParameterValue.LongValue)?.value,
                        (params["enabled"] as? NotificationRouteParameterValue.BooleanValue)?.value,
                        (params["mode"] as? NotificationRouteParameterValue.EnumValue)?.value,
                    ).joinToString(":"),
                )
            },
        )

    private class RecordingScreenFactory : NotificationRouteScreenFactory {
        override fun feedbackTickets(ticketUuid: String?): AppScreenRoute = TestScreen("feedback:$ticketUuid")

        override fun notificationCenter(): AppScreenRoute = TestScreen("notification-center")

        override fun userProfile(): AppScreenRoute = TestScreen("user-profile")

        override fun personalSettings(): AppScreenRoute = TestScreen("personal-settings")

        override fun permissionSettings(): AppScreenRoute = TestScreen("permission-settings")

        override fun settings(): AppScreenRoute = TestScreen("settings")
    }

    private data class TestScreen(override val routeKey: String) : AppScreenRoute {
        @Composable
        override fun Content() = Unit
    }
}
