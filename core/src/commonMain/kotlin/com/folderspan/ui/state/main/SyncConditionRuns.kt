package com.folderspan.ui.state.main

import androidx.compose.runtime.mutableStateMapOf
import com.folderspan.service.sync.SyncExecutionConditions
import com.folderspan.service.sync.SyncPlatformConditions
import com.folderspan.service.sync.SyncRunCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import strings.AppStrings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class SyncConditionRun(val task: SyncTask, val run: suspend () -> Unit, val ignore: Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncConditionRun>
    var canceled = false
    var interrupted = emptySet<SyncRunCondition>()
    var published = false
    var trigger = "manual"
    var startedAt = 0L
}

/** Keeps deferred requests outside the global run permits; definitions and ownership are checked again on dispatch. */
internal class SyncConditionRuns(
    private val scope: CoroutineScope,
    private val conditions: () -> StateFlow<SyncPlatformConditions>,
    private val current: (Long) -> SyncTask?,
    private val owned: (SyncTask) -> Boolean,
    private val active: (Long) -> Boolean,
    private val dispatch: (SyncTask, suspend () -> Unit) -> Boolean,
    private val interrupted: suspend (SyncConditionRun) -> Unit,
    private val deferred: (SyncTask) -> Unit,
    private val userCanceled: (Long) -> Boolean = { false },
) {
    private val lock = TaskRuntimeStoreLock()
    private val requests = mutableMapOf<Long, SyncConditionRun>()
    private val waiting = mutableMapOf<Long, SyncConditionRun>()
    val waitingConditions = mutableStateMapOf<Long, Set<SyncRunCondition>>()
    private var listener: Job? = null

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }

    fun unmet(task: SyncTask): Set<SyncRunCondition> =
        if (!task.requireWifi && !task.requireCharging) emptySet()
        else conditions().value.unmetConditions(task.requireWifi, task.requireCharging)

    private fun valid(request: SyncConditionRun): Boolean = !request.canceled && !userCanceled(request.task.id) && current(request.task.id)?.let {
        owned(it) && syncPreviewFingerprint(it) == syncPreviewFingerprint(request.task)
    } == true

    private fun defer(request: SyncConditionRun) {
        locked {
            if (!valid(request)) return
            requests[request.task.id] = request
            waiting[request.task.id] = request
            waitingConditions[request.task.id] = unmet(request.task).ifEmpty { request.interrupted }
            if (listener == null) listener = scope.launch {
                conditions().collect { withContext(Dispatchers.Main) { drain() } }
            }
        }
        deferred(request.task)
    }

    fun offer(task: SyncTask, ignore: Boolean = false, run: suspend () -> Unit): Boolean {
        if (locked { waiting[task.id]?.let { valid(it) && !ignore } == true }) return true
        val request = SyncConditionRun(task, run, ignore)
        if (!ignore && unmet(task).isNotEmpty()) { defer(request); return true }
        return dispatch(request)
    }

    private fun dispatch(request: SyncConditionRun): Boolean = locked {
        val previous = requests[request.task.id]
        val deferred = waiting.remove(request.task.id)
        waitingConditions.remove(request.task.id)
        requests[request.task.id] = request
        val accepted = dispatch(request.task) {
            try { withContext(request) { request.run() } }
            finally { locked {
                if (requests[request.task.id] === request && waiting[request.task.id] !== request) requests.remove(request.task.id)
            } }
        }
        if (!accepted && requests[request.task.id] === request) {
            if (previous == null) requests.remove(request.task.id) else requests[request.task.id] = previous
            if (deferred != null) {
                waiting[request.task.id] = deferred
                waitingConditions[request.task.id] = unmet(deferred.task)
            }
        }
        accepted
    }

    fun drain() {
        for (request in locked { waiting.values.toList() }) {
            if (!valid(request)) { cancel(request.task.id); continue }
            val missing = unmet(request.task)
            locked { if (waiting[request.task.id] === request) waitingConditions[request.task.id] = missing }
            if (missing.isEmpty() && !active(request.task.id)) locked {
                if (waiting[request.task.id] === request) dispatch(request)
            }
        }
    }

    fun cancel(taskId: Long): Boolean = locked {
        requests.remove(taskId)?.canceled = true
        waitingConditions.remove(taskId)
        waiting.remove(taskId) != null
    }

    suspend fun deferIfNeeded(task: SyncTask, runAgain: suspend () -> Unit): Boolean {
        val request = currentCoroutineContext()[SyncConditionRun] ?: SyncConditionRun(task, runAgain, false)
        if (request.ignore || unmet(task).isEmpty()) return false
        defer(request)
        return true
    }

    suspend fun execute(task: SyncTask, runAgain: suspend () -> Unit, trigger: String = "manual", block: suspend () -> Unit) {
        val request = currentCoroutineContext()[SyncConditionRun] ?: SyncConditionRun(task, runAgain, false)
        if (!request.ignore && unmet(task).isNotEmpty()) { defer(request); return }
        locked {
            requests[task.id] = request
            request.interrupted = emptySet()
            request.published = false
            request.trigger = trigger
            request.startedAt = nowMillis()
        }
        try {
            withContext(request) {
                if (request.ignore || !task.requireWifi && !task.requireCharging || !conditions().value.supportsConditions) block()
                else coroutineScope {
                    val owner = this
                    val gate = SyncExecutionConditions(this, { unmet(task) }, { missing ->
                        request.interrupted = missing
                        owner.cancel(CancellationException("Synchronization run conditions no longer met"))
                    })
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { conditions().collect { gate.check() } }
                    try { withContext(gate) { block() } }
                    finally { withContext(NonCancellable) { watcher.cancelAndJoin(); gate.close() } }
                }
            }
        } catch (cancel: CancellationException) {
            if (request.interrupted.isEmpty()) throw cancel
        } finally {
            if (request.interrupted.isNotEmpty()) withContext(NonCancellable) {
                withContext(request) { if (!request.published) interrupted(request) }
                defer(request)
            }
            locked { if (requests[task.id] === request && waiting[task.id] !== request) requests.remove(task.id) }
        }
    }
}

fun SyncState.unmetRunConditions(task: SyncTask): Set<SyncRunCondition> = conditionRuns.unmet(task)

internal fun syncConditionsLostMessage(conditions: Set<SyncRunCondition>): String =
    AppStrings.sync_conditions_lost.format(arg0 = conditions.joinToString(", ") {
        when (it) { SyncRunCondition.Wifi -> AppStrings.sync_condition_wifi; SyncRunCondition.Charging -> AppStrings.sync_condition_charging }
    })
