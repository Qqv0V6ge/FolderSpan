package com.folderspan.service.session

import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult

/** Optional platform acceleration. Native and browser-owned carriers keep their shared flow path. */
internal interface HostDeviceFileClient {
    val hostTransfersAvailable: Boolean
    suspend fun hostFileTransfer(
        open: DeviceSessionStreamOpen,
        transfer: DeviceSessionHostTransfer,
        onProgress: suspend (Map<String, Long>) -> Unit,
        isPaused: suspend () -> Boolean = { false },
    ): Result<FolderSpanArchiveTransferResult>
    suspend fun hostRelay(
        target: HostDeviceFileClient,
        sourceOpen: DeviceSessionStreamOpen,
        targetOpen: DeviceSessionStreamOpen,
        transfer: DeviceSessionHostTransfer,
        onProgress: suspend (Map<String, Long>) -> Unit,
        isPaused: suspend () -> Boolean = { false },
    ): Result<FolderSpanArchiveTransferResult>
}
