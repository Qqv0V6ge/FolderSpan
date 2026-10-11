package com.folderspan.service.session

import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.http.archive.FolderSpanArchiveWriteRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

/** Framing and credit belong to the carrier; business authorization stays with the peer. */
internal interface DeviceSessionFrameConnection {
    val incomingEvents: ReceiveChannel<DeviceSessionFrame>
    val supportsHostTransfers: Boolean get() = false
    val hostTransportHandle: String? get() = null
    suspend fun openStream(payload: ByteArray = ByteArray(0), onStreamAllocated: suspend (Int) -> Unit = {}): Int
    suspend fun acceptRemoteStream(streamId: Int): Boolean
    suspend fun sendData(streamId: Int, payload: ByteArray)
    suspend fun sendControl(payload: ByteArray)
    suspend fun sendTrailer(streamId: Int, payload: ByteArray = ByteArray(0))
    suspend fun sendWindowUpdate(streamId: Int, creditBytes: Int)
    suspend fun sendRst(streamId: Int, errorCode: Int = 0)
    suspend fun ping(payload: ByteArray): ByteArray
    suspend fun goAway()
    suspend fun close()
    suspend fun receivedGoAway(): DeviceSessionGoAway?

    suspend fun hostTransfer(
        request: DeviceSessionHostTransfer,
        onProgress: suspend (Map<String, Long>) -> Unit = {},
        prepareEntry: (suspend (path: String, size: Long, directory: Boolean) -> Unit)? = null,
        isPaused: suspend () -> Boolean = { false },
    ): FolderSpanArchiveTransferResult? = null
}

internal interface DeviceSessionPlatformTransport {
    val connection: DeviceSessionFrameConnection
    fun start(scope: CoroutineScope): Job
}

internal interface DeviceSessionPlatformChannel : DeviceSessionByteChannel {
    fun frameTransport(
        sendPlan: DeviceSessionWindowPlan,
        receivePlan: DeviceSessionWindowPlan,
        isClient: Boolean,
        onPing: (suspend (ByteArray) -> Unit)?,
    ): DeviceSessionPlatformTransport
}

@Serializable
internal enum class DeviceSessionHostTransferKind { SendFile, ReceiveFile, SendArchive, ReceiveArchive, Relay }

@Serializable
internal data class DeviceSessionHostTransfer(
    val streamId: Int,
    val kind: DeviceSessionHostTransferKind,
    val path: String = "",
    val fileSize: Long = 0,
    val startOffset: Long = 0,
    val endOffset: Long = 0,
    val archiveRead: FolderSpanArchiveReadRequest? = null,
    val archiveWrite: FolderSpanArchiveWriteRequest? = null,
    val callbackId: String = "",
    val targetHandle: String = "",
    val targetStreamId: Int = 0,
    val progressCallbackId: String = "",
    val initiallyPaused: Boolean = false,
)
