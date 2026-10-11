package com.folderspan.ui.screen.device

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDeletionTest {
    @Test
    fun onlyExplicitConfirmationDeletesTheCapturedDevices() = runTest {
        val host = SnackbarHostState()
        val selectedIds = mutableListOf("first", "second")
        val deletedIds = mutableListOf<String>()
        val dismissed = launch(start = CoroutineStart.UNDISPATCHED) {
            confirmDeviceDeletion(host, selectedIds) { deletedIds.addAll(it) }
        }
        assertTrue(deletedIds.isEmpty())
        assertNotNull(host.currentSnackbarData).dismiss()
        dismissed.join()
        assertTrue(deletedIds.isEmpty())

        val confirmed = launch(start = CoroutineStart.UNDISPATCHED) {
            confirmDeviceDeletion(host, selectedIds) { deletedIds.addAll(it) }
        }
        val prompt = assertNotNull(host.currentSnackbarData)
        assertEquals(AppStrings.ui_delete, prompt.visuals.actionLabel)
        assertTrue(prompt.visuals.withDismissAction)
        assertTrue(deletedIds.isEmpty())
        selectedIds.clear()
        selectedIds.add("third")
        prompt.performAction()
        confirmed.join()
        assertEquals(listOf("first", "second"), deletedIds)
        assertNull(host.currentSnackbarData)

        confirmDeviceDeletion(host, emptyList()) { error("Empty selection must not delete") }
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun singleDeviceDeletionShowsItsNameAndRequiresConfirmation() = runTest {
        val host = SnackbarHostState()
        val message = AppStrings.dialog_delete_device.format(deviceName = "My device")
        val deletedIds = mutableListOf<String>()
        for (confirm in listOf(false, true)) {
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                confirmDeviceDeletion(host, listOf("device-id"), message) { deletedIds.addAll(it) }
            }
            val prompt = assertNotNull(host.currentSnackbarData)
            assertEquals(message, prompt.visuals.message)
            assertTrue(deletedIds.isEmpty())
            if (confirm) prompt.performAction() else prompt.dismiss()
            request.join()
            assertEquals(if (confirm) listOf("device-id") else emptyList(), deletedIds)
        }
    }
}
