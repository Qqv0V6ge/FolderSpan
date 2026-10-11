package com.folderspan

import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.SettingsAuthSessionStore
import com.folderspan.test.createInMemorySettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppProAuthSessionInitializationTest {

    @AfterTest
    fun tearDown() {
        SessionManager.initialize(InMemoryAuthSessionStore())
    }

    @Test
    fun initializeProAuthSession_restoresStoredLoginSession() {
        SessionManager.initialize(InMemoryAuthSessionStore())
        val persistedStore = InMemoryAuthSessionStore(
            initialSession = AuthSession(
                accessToken = "stored-token",
                refreshToken = "stored-refresh",
                userEmail = "user@example.com",
            ),
        )

        initializeProAuthSession(persistedStore)

        val restoredSession = SessionManager.currentSession()
        assertEquals("stored-token", restoredSession?.accessToken)
        assertEquals("stored-refresh", restoredSession?.refreshToken)
        assertEquals("user@example.com", restoredSession?.userEmail)
    }

    @Test
    fun initializeProAuthSessionWithSettings_restoresStoredLoginSession() {
        SessionManager.initialize(InMemoryAuthSessionStore())
        val settings = createInMemorySettings()
        SettingsAuthSessionStore(settings).save(
            AuthSession(
                accessToken = "settings-token",
                refreshToken = "settings-refresh",
                userEmail = "settings@example.com",
            ),
        )

        initializeProAuthSession(settings)

        val restoredSession = SessionManager.currentSession()
        assertEquals("settings-token", restoredSession?.accessToken)
        assertEquals("settings-refresh", restoredSession?.refreshToken)
        assertEquals("settings@example.com", restoredSession?.userEmail)
    }
}
