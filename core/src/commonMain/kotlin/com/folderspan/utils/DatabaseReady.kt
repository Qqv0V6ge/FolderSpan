package com.folderspan.utils

import kotlinx.coroutines.CompletableDeferred

object DatabaseReady {
    private val ready = CompletableDeferred<Unit>()

    fun markReady() {
        if (!ready.isCompleted) {
            ready.complete(Unit)
        }
    }

    suspend fun await() {
        ready.await()
    }
}
