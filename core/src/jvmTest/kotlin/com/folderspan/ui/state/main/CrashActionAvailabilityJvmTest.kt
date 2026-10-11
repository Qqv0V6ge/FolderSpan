package com.folderspan.ui.state.main

import kotlin.test.Test
import kotlin.test.assertTrue

class CrashActionAvailabilityJvmTest {
    @Test
    fun defaultAvailabilityAllowsExitOnJvm() {
        val availability = defaultCrashActionAvailability()
        assertTrue(availability.canRestart)
        assertTrue(availability.canExit)
        assertTrue(availability.canCopy)
    }
}
