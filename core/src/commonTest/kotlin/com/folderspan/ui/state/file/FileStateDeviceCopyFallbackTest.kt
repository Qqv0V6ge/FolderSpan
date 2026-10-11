package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.exception.DeviceCopyUnsupportedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileStateDeviceCopyFallbackTest {
    @Test
    fun unsupportedResultUsesLocalFallback() = runTest {
        var fallbackCalled = false

        val result = copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = {
                Result.failure(DeviceCopyUnsupportedException())
            },
            copyViaLocal = {
                fallbackCalled = true
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertTrue(fallbackCalled)
    }

    @Test
    fun thrownUnsupportedErrorUsesLocalFallback() = runTest {
        var fallbackCalled = false

        val result = copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = { throw DeviceCopyUnsupportedException() },
            copyViaLocal = {
                fallbackCalled = true
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertTrue(fallbackCalled)
    }

    @Test
    fun successfulDeviceCopyDoesNotUseFallback() = runTest {
        var fallbackCalled = false

        val result = copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = { Result.success(true) },
            copyViaLocal = {
                fallbackCalled = true
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertFalse(fallbackCalled)
    }

    @Test
    fun ordinaryFailureDoesNotUseFallback() = runTest {
        val expected = IllegalStateException(AppStrings.ui_connection_failed)
        var fallbackCalled = false

        val result = copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = { Result.failure(expected) },
            copyViaLocal = {
                fallbackCalled = true
                Result.success(true)
            },
        )

        assertSame(expected, result.exceptionOrNull())
        assertFalse(fallbackCalled)
    }

    @Test
    fun falseResultDoesNotUseFallback() = runTest {
        var fallbackCalled = false

        val result = copyDeviceFileWithUnsupportedFallback(
            copyOnDevice = { Result.success(false) },
            copyViaLocal = {
                fallbackCalled = true
                Result.success(true)
            },
        )

        assertEquals(false, result.getOrThrow())
        assertFalse(fallbackCalled)
    }
}
