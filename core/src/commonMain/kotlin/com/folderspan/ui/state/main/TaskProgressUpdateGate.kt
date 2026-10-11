package com.folderspan.ui.state.main

import kotlin.time.Clock

internal class TaskProgressUpdateGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var lastPublishedAt: Long? = null

    fun shouldPublish(force: Boolean = false): Boolean {
        val now = nowMs()
        val last = lastPublishedAt
        val shouldPublish = force || last == null || now - last >= minIntervalMs
        if (shouldPublish) {
            lastPublishedAt = now
        }
        return shouldPublish
    }

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}
