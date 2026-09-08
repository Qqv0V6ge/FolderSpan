package com.folderspan.service.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

@OptIn(DelicateCoroutinesApi::class)
internal suspend fun <T> consumeUploadChunksBlocking(
    readChunk: suspend () -> ByteArray?,
    block: suspend (nextChunk: () -> ByteArray?) -> T,
): T {
    val producerContext = newSingleThreadContext("network-upload-source")
    val chunks = Channel<ByteArray>(capacity = 1)
    val producer = CoroutineScope(producerContext + SupervisorJob()).launch {
        try {
            while (isActive) {
                val chunk = readChunk() ?: break
                if (chunk.isEmpty()) continue
                chunks.send(chunk)
            }
            chunks.close()
        } catch (error: Throwable) {
            chunks.close(error)
        }
    }
    try {
        return block {
            runBlocking {
                val result = chunks.receiveCatching()
                result.exceptionOrNull()?.let { throw it }
                result.getOrNull()
            }
        }
    } finally {
        producer.cancelAndJoin()
        producerContext.close()
    }
}
