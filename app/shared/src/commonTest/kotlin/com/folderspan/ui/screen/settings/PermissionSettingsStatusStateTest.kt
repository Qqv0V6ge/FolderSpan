package com.folderspan.ui.screen.settings

import com.folderspan.permission.PermissionAction
import com.folderspan.permission.PermissionIds
import com.folderspan.permission.PermissionStatus
import com.folderspan.permission.PlatformPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionSettingsStatusStateTest {
    private val permission = PlatformPermission(PermissionIds.Notifications, PermissionAction.RequestOnce, "通知", "通知")

    @Test
    fun lateQueryCannotOverwriteANewerAuthorizationResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<PermissionStatus>()
        val state = PermissionSettingsStatusState {
            started.complete(Unit)
            result.await()
        }
        val refresh = launch { state.refresh(listOf(permission)) }
        started.await()
        state.onRequestResult(permission.id, PermissionStatus.Granted)
        result.complete(PermissionStatus.Denied)
        refresh.join()
        assertEquals(PermissionStatus.Granted, state.statuses[permission.id])
        assertFalse(state.refreshing)
    }

    @Test
    fun aFreshCheckCanObservePermissionRevokedAfterAuthorization() = runTest {
        val state = PermissionSettingsStatusState { PermissionStatus.Denied }
        state.onRequestResult(permission.id, PermissionStatus.Granted)
        state.refresh(listOf(permission))
        assertEquals(PermissionStatus.Denied, state.statuses[permission.id])
    }

    @Test
    fun resumeDuringAnInFlightCheckIsRecheckedAfterTheOldReadCompletes() = runTest {
        val started = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<PermissionStatus>()
        var reads = 0
        val state = PermissionSettingsStatusState {
            reads++
            if (reads == 1) {
                started.complete(Unit)
                oldResult.await()
            } else {
                PermissionStatus.Granted
            }
        }
        val initial = launch { state.refresh(listOf(permission)) }
        started.await()
        val resumed = launch { state.refresh(listOf(permission)) }
        runCurrent()
        oldResult.complete(PermissionStatus.Denied)
        initial.join()
        resumed.join()
        assertEquals(2, reads)
        assertEquals(PermissionStatus.Granted, state.statuses[permission.id])
    }

    @Test
    fun failedQueriesRequireManualConfirmationWithoutClaimingDenial() = runTest {
        val state = PermissionSettingsStatusState { error("Native permission service unavailable") }
        state.refresh(listOf(permission))
        assertEquals(PermissionStatus.Unknown, state.statuses[permission.id])
        assertFalse(state.refreshing)
    }

    @Test
    fun cancellationIsPropagatedAndAllowsTheNextRefresh() = runTest {
        val state = PermissionSettingsStatusState { throw CancellationException("Page closed") }
        var cancelled = false
        try {
            state.refresh(listOf(permission))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(state.statuses.isEmpty())
        assertFalse(state.refreshing)
    }
}
