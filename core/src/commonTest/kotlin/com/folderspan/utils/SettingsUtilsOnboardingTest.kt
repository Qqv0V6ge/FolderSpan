package com.folderspan.utils

import com.folderspan.test.createInMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsUtilsOnboardingTest {
    @Test
    fun onboardingCompletedDefaultsToFalseAndCanBeUpdated() {
        SettingsUtils.init(createInMemorySettings())

        assertFalse(SettingsUtils.isOnboardingCompleted())

        SettingsUtils.setOnboardingCompleted(true)

        assertTrue(SettingsUtils.isOnboardingCompleted())
    }

    @Test
    fun welcomeAgreementAcceptedDefaultsToFalseAndCanBeUpdated() {
        SettingsUtils.init(createInMemorySettings())

        assertFalse(SettingsUtils.isWelcomeAgreementAccepted())

        SettingsUtils.setWelcomeAgreementAccepted(true)

        assertTrue(SettingsUtils.isWelcomeAgreementAccepted())
    }

    @Test
    fun appUpdateChannelDefaultsToReleaseAndPersistsSelection() {
        SettingsUtils.init(createInMemorySettings())

        assertEquals(SettingsUtils.DEFAULT_APP_UPDATE_CHANNEL, SettingsUtils.appUpdateChannel())

        SettingsUtils.setAppUpdateChannel("beta")

        assertEquals("beta", SettingsUtils.appUpdateChannel())
    }
}
