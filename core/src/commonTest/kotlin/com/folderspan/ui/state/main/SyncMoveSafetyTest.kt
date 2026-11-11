package com.folderspan.ui.state.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncMoveSafetyTest {
    private val file = SyncEntryState(false, 10, 10_000)

    @Test fun verifiedCopyPrecedesSourceDeletionAndFailuresPreserveSource() = runTest {
        for (failure in listOf("none", "copy", "size", "changed", "changed-near", "delete", "exists", "unreadable", "unknown")) {
            var source: Map<String, SyncEntryState>? = mapOf("" to if (failure == "unknown") file.copy(modifiedAt = 0) else file)
            var target: Map<String, SyncEntryState>? = if (failure == "exists") mapOf("" to file) else null
            var deleteCalls = 0
            val events = mutableListOf<String>()
            val result = verifiedSyncDeviceMove(
                readSource = { events += "source"; source },
                readTarget = { events += "target"; if (failure == "unreadable") error("unreadable") else target },
                copy = {
                    events += "copy"
                    if (failure == "changed") source = mapOf("" to file.copy(modifiedAt = 20_000))
                    if (failure == "changed-near") source = mapOf("" to file.copy(modifiedAt = 10_001))
                    target = mapOf("" to if (failure == "size") file.copy(size = 9) else file.copy(modifiedAt = 99_999))
                    failure != "copy"
                },
                delete = { events += "delete"; deleteCalls++; if (failure == "delete") false else { source = null; true } },
            )
            assertEquals(failure == "none", result.isSuccess, failure)
            assertEquals(if (failure in listOf("none", "delete")) 1 else 0, deleteCalls, failure)
            if (failure != "none") assertTrue(source != null, failure)
            else assertEquals(listOf("source", "target", "copy", "target", "source", "delete"), events)
        }
    }

    @Test fun directoryVerificationRequiresEveryChildAndCancellationNeverDeletes() = runTest {
        val source = mapOf("" to SyncEntryState(true, 0, 0), "a" to file, "nested/b" to file)
        var target: Map<String, SyncEntryState>? = null
        var deleted = false
        assertTrue(verifiedSyncDeviceMove({ source }, { target }, {
            target = source - "nested/b"; true
        }, { deleted = true; true }).isFailure)
        assertTrue(!deleted)
        target = null
        assertFailsWith<CancellationException> {
            verifiedSyncDeviceMove({ source }, { target }, { throw CancellationException("cancel") }, { deleted = true; true })
        }
        assertTrue(!deleted)
    }
}
