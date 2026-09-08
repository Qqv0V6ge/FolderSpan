package com.folderspan.ui.state.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashScreenStateTest {
    @Test
    fun toScreenStateFormatsReportText() {
        val info = CrashInfo(
            name = "TestError",
            message = "boom",
            stackTrace = "line1\nline2"
        )
        val availability = CrashActionAvailability(
            canRestart = true,
            canExit = false,
            canCopy = true
        )

        val state = info.toScreenState(availability)

        assertEquals(info, state.info)
        assertEquals(availability.canRestart, state.canRestart)
        assertEquals(availability.canExit, state.canExit)
        assertEquals(availability.canCopy, state.canCopy)
        assertTrue(state.reportText.contains("TestError"))
        assertTrue(state.reportText.contains("boom"))
        assertTrue(state.reportText.contains("line1"))
    }
}
