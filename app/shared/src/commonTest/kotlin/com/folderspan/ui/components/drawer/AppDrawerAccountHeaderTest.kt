package com.folderspan.ui.components.drawer

import com.folderspan.appDrawerAccountHeader
import com.folderspan.appDrawerAccountHeaderClickRoute
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.withUserProfileSnapshot
import com.folderspan.pro.presentation.navigation.ProRoutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDrawerAccountHeaderTest {

    @Test
    fun appDrawerAccountHeader_returnsDefaultTitleWhenLoggedOut() {
        val header = appDrawerAccountHeader(null)

        assertEquals("FolderSpan", header.title)
        assertNull(header.subtitle)
        assertNull(header.avatarLabel)
    }

    @Test
    fun appDrawerAccountHeader_prefersLoginDataUserNameAndAvatarWhenLoggedIn() {
        val session = AuthSession(
            accessToken = "token-value",
            userEmail = "fallback@example.com",
            loginData = Json.parseToJsonElement(
                """
                {
                  "token": "token-value",
                  "email": "fallback@example.com",
                  "user": {
                    "name": "Alice Chen",
                    "avatar": "https://example.com/avatar.png"
                  }
                }
                """.trimIndent(),
            ) as JsonObject,
        )

        val header = appDrawerAccountHeader(session)

        assertEquals("Alice Chen", header.title)
        assertEquals("fallback@example.com", header.subtitle)
        assertEquals("A", header.avatarLabel)
        assertEquals("https://example.com/avatar.png", header.avatarUrl)
    }

    @Test
    fun appDrawerAccountHeader_prefersLatestProfileSnapshotOverStaleLoginData() {
        val session = AuthSession(
            accessToken = "token-value",
            userEmail = "old@example.com",
            loginData = Json.parseToJsonElement(
                """
                {
                  "name": "Old name",
                  "avatar": "https://example.com/old.png",
                  "user": {
                    "name": "Old name",
                    "avatar": "https://example.com/old.png"
                  }
                }
                """.trimIndent(),
            ) as JsonObject,
        ).withUserProfileSnapshot(
            name = "111",
            email = "new@example.com",
            avatar = "https://example.com/new.png",
        )

        val header = appDrawerAccountHeader(session)

        assertEquals("111", header.title)
        assertEquals("new@example.com", header.subtitle)
        assertEquals("1", header.avatarLabel)
        assertEquals("https://example.com/new.png", header.avatarUrl)
    }

    @Test
    fun appDrawerAccountHeader_doesNotReuseStaleAvatarAfterProfileRemoval() {
        val session = AuthSession(
            accessToken = "token-value",
            loginData = Json.parseToJsonElement(
                """{"user":{"name":"Old name","avatar":"https://example.com/old.png"}}""",
            ) as JsonObject,
        ).withUserProfileSnapshot(
            name = "Current name",
            email = "user@example.com",
            avatar = null,
        )

        val header = appDrawerAccountHeader(session)

        assertEquals("Current name", header.title)
        assertNull(header.avatarUrl)
    }

    @Test
    fun appDrawerAccountHeaderClickRoute_returnsUserProfileRoute() {
        val route = assertNotNull(appDrawerAccountHeaderClickRoute())

        assertEquals(ProRoutes.userProfileScreen().routeKey, route.routeKey)
    }
}
