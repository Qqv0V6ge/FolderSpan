package com.folderspan.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SnackbarTest {
    @Test
    fun latestSnackbarReplacesCurrentSnackbar() = runTest {
        val hostState = SnackbarHostState()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            hostState.showLatestSnackbar("first")
        }

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            hostState.showLatestSnackbar("second")
        }
        yield()
        yield()

        assertEquals("second", assertNotNull(hostState.currentSnackbarData).visuals.message)
        assertEquals(SnackbarResult.Dismissed, first.await())
        hostState.currentSnackbarData?.dismiss()
        assertEquals(SnackbarResult.Dismissed, second.await())
    }

    @Test
    fun confirmationRunsOnlyAfterAction() = runTest {
        val hostState = SnackbarHostState()
        var confirmations = 0

        val dismissed = async(start = CoroutineStart.UNDISPATCHED) {
            hostState.confirmSnackbarAction("confirm", "delete") { confirmations++ }
        }
        assertNotNull(hostState.currentSnackbarData).dismiss()
        dismissed.await()
        assertEquals(0, confirmations)

        val confirmed = async(start = CoroutineStart.UNDISPATCHED) {
            hostState.confirmSnackbarAction("confirm", "delete") { confirmations++ }
        }
        assertNotNull(hostState.currentSnackbarData).performAction()
        confirmed.await()
        assertEquals(1, confirmations)
    }
}
