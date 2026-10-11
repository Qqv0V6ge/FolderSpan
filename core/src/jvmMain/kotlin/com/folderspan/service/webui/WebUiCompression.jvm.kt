package com.folderspan.service.webui

import com.folderspan.service.http.archive.FolderSpanArchivePlatformCompression
import com.folderspan.service.http.server.linkshare.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class PlatformCompression {
    val input = Channel<ByteArray>(1)
    val output = Channel<ByteArray>(1)
    val readMutex = Mutex()
    val writeMutex = Mutex()
    lateinit var job: Job
    suspend fun close() { input.cancel(); output.cancel(); job.cancelAndJoin() }
}

internal suspend fun compressionRequest(request: LinkShareHttpRequest, session: WebUiPlatformSession): LinkShareHttpResponse {
    if (request.method == "POST" && request.path == "$WEB_UI_PLATFORM_PATH/compression") {
        val input = request.platformBody<WebUiCompressionOpen>()
        require(input.codec in FolderSpanArchivePlatformCompression.supportedCodecs)
        val stream = PlatformCompression()
        stream.job = session.scope.launch(start = CoroutineStart.LAZY) {
            try {
                val transformed = if (input.decompress) FolderSpanArchivePlatformCompression.decompress(input.codec, stream.input.consumeAsFlow())
                else FolderSpanArchivePlatformCompression.compress(input.codec, stream.input.consumeAsFlow())
                transformed.collect { stream.output.send(it) }
                stream.output.close()
            } catch (error: Throwable) { stream.output.close(error); if (error is CancellationException) throw error }
            finally { stream.input.cancel() }
        }
        val id = session.own(stream) { stream.close() }
        stream.job.start()
        return platformResponse(WebUiHandle(id))
    }
    val stream = session.resource(requireNotNull(request.query("handle"))) as? PlatformCompression ?: error("not a compression stream")
    return when (request.method) {
        "PUT" -> stream.writeMutex.withLock { stream.input.send(request.boundedBytes()); platformResponse(true) }
        "POST" -> stream.writeMutex.withLock { stream.input.close(); platformResponse(true) }
        "GET" -> stream.readMutex.withLock {
            val bytes = stream.output.receiveCatching()
            bytes.exceptionOrNull()?.let { throw it }
            LinkShareHttpResponse.bytes(bytes = bytes.getOrNull() ?: byteArrayOf(), headers = listOf(LinkShareHttpHeader("X-FolderSpan-End", bytes.isClosed.toString())))
        }
        else -> LinkShareHttpResponse.text(405, "invalid compression operation")
    }
}
