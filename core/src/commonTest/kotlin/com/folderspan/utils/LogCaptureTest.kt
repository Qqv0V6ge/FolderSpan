package com.folderspan.utils

import strings.AppStrings

import io.github.aakira.napier.LogLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogCaptureTest {
    @AfterTest
    fun tearDown() {
        LogCapture.setErrorListener(null)
        LogCapture.setEnabled(false)
        LogCapture.clear()
    }

    @Test
    fun disabledRecordsNothing() {
        LogCapture.setEnabled(false)
        LogCapture.append(LogLevel.ERROR, "tag", null, "boom")
        assertEquals(0, LogCapture.snapshot().size)
    }

    @Test
    fun overflowKeepsNewestLinesAndStaysWithinCap() {
        LogCapture.setEnabled(true)
        val chunk = "x".repeat(200_000)
        repeat(20) { index ->
            LogCapture.append(LogLevel.INFO, "tag", null, "LINE-$index-$chunk")
        }
        val snapshot = LogCapture.snapshot()
        assertTrue(snapshot.size <= LOG_CAPTURE_MAX_BYTES)
        val text = snapshot.decodeToString()
        assertTrue(text.contains("LINE-19-"))
        assertFalse(text.contains("LINE-0-"))
    }

    @Test
    fun disableClearsSnapshot() {
        LogCapture.setEnabled(true)
        LogCapture.append(LogLevel.INFO, "tag", null, "keep-me")
        assertTrue(LogCapture.snapshot().isNotEmpty())
        LogCapture.setEnabled(false)
        assertEquals(0, LogCapture.snapshot().size)
    }

    @Test
    fun snapshotIsUtf8LogBytes() {
        LogCapture.setEnabled(true)
        LogCapture.append(LogLevel.ERROR, "FileState.kt:10", null, AppStrings.ui_copy_failed)
        val snapshot = LogCapture.snapshot()
        val text = snapshot.decodeToString()
        assertTrue(text.contains("[ERROR]"))
        assertTrue(text.contains(AppStrings.ui_copy_failed))
        assertTrue(text.endsWith("\n"))
    }
}
