package com.folderspan.ui.screen.mcp

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpTokenDeletionTest {
    @Test
    fun onlyDeleteActionConfirmsTokenDeletion() = runTest {
        val host = SnackbarHostState()
        var confirmations = 0

        val dismissed = launch(start = CoroutineStart.UNDISPATCHED) {
            confirmMcpTokenDeletion(host) { confirmations++ }
        }
        assertNotNull(host.currentSnackbarData).dismiss()
        dismissed.join()
        assertEquals(0, confirmations)

        val confirmed = launch(start = CoroutineStart.UNDISPATCHED) {
            confirmMcpTokenDeletion(host) { confirmations++ }
        }
        val prompt = assertNotNull(host.currentSnackbarData)
        assertEquals(AppStrings.ui_mcp_delete_confirmation, prompt.visuals.message)
        assertEquals(AppStrings.ui_delete, prompt.visuals.actionLabel)
        assertTrue(prompt.visuals.withDismissAction)
        prompt.performAction()
        confirmed.join()
        assertEquals(1, confirmations)
    }
}
