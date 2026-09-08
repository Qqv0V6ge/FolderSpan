package com.folderspan.service.file

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

enum class RemoteCopyStatus {
    Running,
    Paused,
    Cancelled,
}

object RemoteCopyControlRegistry {
    private val mutex = Mutex()
    private val slots = mutableMapOf<String, RemoteCopySlot>()

    suspend fun register(requestId: String, ownerToken: String): Boolean {
        return !(requestId.isBlank() || ownerToken.isBlank()) && mutex.withLock {
            if (slots.containsKey(requestId)) {
                false
            } else {
                slots[requestId] = RemoteCopySlot(
                    status = RemoteCopyStatus.Running,
                    ownerToken = ownerToken,
                )
                true
            }
        }
    }

    suspend fun update(
        requestId: String,
        status: RemoteCopyStatus,
        callerToken: String,
    ): Boolean {
        return !(requestId.isBlank() || callerToken.isBlank()) && mutex.withLock {
            val slot = slots[requestId] ?: return@withLock false
            if (!ownerMatches(slot.ownerToken, callerToken)) return@withLock false
            slots[requestId] = slot.copy(status = status)
            true
        }
    }

    suspend fun awaitRunning(
        requestId: String,
        onPaused: (suspend () -> Unit)? = null,
    ): Boolean {
        if (requestId.isBlank()) return false
        var pauseNotified = false
        while (true) {
            val status = mutex.withLock { slots[requestId]?.status ?: return false }
            when (status) {
                RemoteCopyStatus.Running -> return true
                RemoteCopyStatus.Paused -> {
                    if (!pauseNotified) {
                        onPaused?.invoke()
                        pauseNotified = true
                    }
                    delay(150.milliseconds)
                }

                RemoteCopyStatus.Cancelled -> return false
            }
        }
    }

    suspend fun remove(requestId: String) {
        if (requestId.isBlank()) return
        mutex.withLock {
            slots.remove(requestId)
        }
    }

    private data class RemoteCopySlot(
        val status: RemoteCopyStatus,
        val ownerToken: String,
    )
}

private fun ownerMatches(ownerToken: String, callerToken: String): Boolean {
    val ownerBytes = ownerToken.encodeToByteArray()
    val callerBytes = callerToken.encodeToByteArray()
    if (ownerBytes.size != callerBytes.size) return false
    var diff = 0
    for (index in ownerBytes.indices) {
        diff = diff or (ownerBytes[index].toInt() xor callerBytes[index].toInt())
    }
    return diff == 0
}
