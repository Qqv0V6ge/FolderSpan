package com.folderspan.service.operation

internal actual object HttpTransferRuntimeMemoryStatusProvider {
    actual fun sample(): HttpTransferRuntimeMemoryStatus =
        HttpTransferRuntimeMemoryStatus.unknown()
}
