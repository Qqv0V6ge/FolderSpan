package com.folderspan.ui.state.main

internal const val TASK_PROGRESS_RATE_SAMPLE_INTERVAL_MS = 1_000L
private const val TASK_PROGRESS_RATE_SMOOTHING_ALPHA = 0.35
private const val TASK_PROGRESS_RATE_STALL_SAMPLES = 3

internal data class TaskProgressRateSample(
    val speedPerSecond: Double,
    val etaMs: Long,
)

internal class TaskProgressRateSampler(
    initialCompleted: Long = 0L,
    initialAt: Long,
    private val sampleIntervalMs: Long = TASK_PROGRESS_RATE_SAMPLE_INTERVAL_MS,
) {
    private var sampleCompleted = initialCompleted.coerceAtLeast(0L)
    private var sampleAt = initialAt.coerceAtLeast(0L)
    private var currentSpeedPerSecond = 0.0
    private var currentEtaMs = -1L
    private var hasSpeedSample = false
    private var consecutiveZeroSamples = 0

    fun current(): TaskProgressRateSample {
        return TaskProgressRateSample(
            speedPerSecond = currentSpeedPerSecond,
            etaMs = currentEtaMs,
        )
    }

    fun update(
        completed: Long,
        total: Long,
        now: Long,
        force: Boolean = false,
    ): TaskProgressRateSample {
        val hasKnownTotal = total > 0L
        val normalizedTotal = if (hasKnownTotal) total else completed.coerceAtLeast(0L)
        val normalizedCompleted = if (hasKnownTotal) {
            completed.coerceIn(0L, normalizedTotal)
        } else {
            completed.coerceAtLeast(0L)
        }
        val normalizedNow = now.coerceAtLeast(sampleAt)
        val remaining = if (hasKnownTotal) {
            (normalizedTotal - normalizedCompleted).coerceAtLeast(0L)
        } else {
            -1L
        }
        val elapsed = (normalizedNow - sampleAt).coerceAtLeast(0L)
        val shouldSample = force || (hasKnownTotal && remaining == 0L) || elapsed >= sampleIntervalMs
        if (!shouldSample) return current()

        val deltaCompleted = (normalizedCompleted - sampleCompleted).coerceAtLeast(0L)
        val measuredSpeed = if (deltaCompleted > 0L && elapsed > 0L) {
            deltaCompleted * 1_000.0 / elapsed
        } else {
            0.0
        }
        val speed = when {
            measuredSpeed > 0.0 -> {
                consecutiveZeroSamples = 0
                if (!hasSpeedSample || currentSpeedPerSecond <= 0.0) {
                    measuredSpeed
                } else {
                    currentSpeedPerSecond +
                        TASK_PROGRESS_RATE_SMOOTHING_ALPHA * (measuredSpeed - currentSpeedPerSecond)
                }
            }
            !hasSpeedSample -> {
                consecutiveZeroSamples = 1
                0.0
            }
            else -> {
                consecutiveZeroSamples++
                if (consecutiveZeroSamples >= TASK_PROGRESS_RATE_STALL_SAMPLES) {
                    0.0
                } else {
                    currentSpeedPerSecond * (1.0 - TASK_PROGRESS_RATE_SMOOTHING_ALPHA)
                }
            }
        }
        val etaMs = when {
            !hasKnownTotal -> -1L
            remaining == 0L -> 0L
            speed > 0.0 -> ((remaining / speed) * 1_000).toLong()
            else -> -1L
        }

        sampleCompleted = normalizedCompleted
        sampleAt = normalizedNow
        hasSpeedSample = true
        currentSpeedPerSecond = speed
        currentEtaMs = etaMs
        return current()
    }
}
