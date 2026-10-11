package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileStateNetworkCopyFallbackTest {
    @Test
    fun unsupportedServerCopyFallsBackToLocalTransfer() = runSuspendTest {
        var serverCalls = 0
        var localCalls = 0

        val result = copyNetworkFileWithUnsupportedFallback(
            copyOnServer = {
                serverCalls++
                Result.failure(NetworkUnsupportedException(AppStrings.ui_test_file_state_network_copy_fallback_the_server_does_not_support_direct))
            },
            copyViaLocal = {
                localCalls++
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(1, serverCalls)
        assertEquals(1, localCalls)
    }

    @Test
    fun thrownUnsupportedServerCopyAlsoFallsBackToLocalTransfer() = runSuspendTest {
        var localCalls = 0

        val result = copyNetworkFileWithUnsupportedFallback(
            copyOnServer = { throw NetworkUnsupportedException(AppStrings.ui_test_file_state_network_copy_fallback_the_server_does_not_support_direct) },
            copyViaLocal = {
                localCalls++
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(1, localCalls)
    }

    @Test
    fun successfulServerCopyDoesNotFallBack() = runSuspendTest {
        var localCalls = 0

        val result = copyNetworkFileWithUnsupportedFallback(
            copyOnServer = { Result.success(true) },
            copyViaLocal = {
                localCalls++
                Result.success(true)
            },
        )

        assertTrue(result.getOrThrow())
        assertEquals(0, localCalls)
    }

    @Test
    fun ordinaryServerCopyFailureDoesNotFallBack() = runSuspendTest {
        var localCalls = 0

        val result = copyNetworkFileWithUnsupportedFallback(
            copyOnServer = { Result.failure(IllegalStateException(AppStrings.ui_insufficient_permissions)) },
            copyViaLocal = {
                localCalls++
                Result.success(true)
            },
        )

        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals(0, localCalls)
    }

    @Test
    fun unsuccessfulServerResultWithoutUnsupportedErrorDoesNotFallBack() = runSuspendTest {
        var localCalls = 0

        val result = copyNetworkFileWithUnsupportedFallback(
            copyOnServer = { Result.success(false) },
            copyViaLocal = {
                localCalls++
                Result.success(true)
            },
        )

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
        assertEquals(0, localCalls)
    }
}
