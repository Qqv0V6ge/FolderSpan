package com.folderspan.service.network

import kotlinx.serialization.Serializable

@Serializable
enum class StorageContentErrorCode {
    Unavailable, Locked, UnlockFailed, UnsupportedFormat, DamagedLocation,
    InitializationConflict, ReadOnly, UncertainResult, Conflict, SourceChanged,
}

open class StorageContentException(val code: StorageContentErrorCode, message: String) : Exception(message)
