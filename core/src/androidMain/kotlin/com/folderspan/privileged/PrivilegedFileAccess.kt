package com.folderspan.privileged

import com.folderspan.utils.isPermissionError

object PrivilegedFileAccess {
    suspend fun awaitPreferredBackendReady(): Boolean =
        awaitPreferredBackendReady(PrivilegedFileBackends.defaults())

    internal suspend fun awaitPreferredBackendReady(
        backends: List<PrivilegedFileBackend>
    ): Boolean {
        val backend = backends.firstOrNull { item -> item.isAuthorized() }
            ?: return true
        return backend.awaitReady()
    }

    fun <T> withFallback(
        primary: () -> Result<T>,
        operation: (PrivilegedFileClient) -> Result<T>
    ): Result<T> = withFallback(
        primary = primary,
        backends = PrivilegedFileBackends.defaults(),
        operation = operation
    )

    fun <T> withFallback(
        primary: () -> Result<T>,
        backends: List<PrivilegedFileBackend>,
        operation: (PrivilegedFileClient) -> Result<T>
    ): Result<T> {
        backends.firstOrNull { item -> item.usesBeforeLocalIo() && item.isAuthorized() }?.let { backend ->
            return backend.withClient(operation)
        }

        val primaryResult = primary()
        if (primaryResult.isSuccess) return primaryResult

        val originalError = primaryResult.exceptionOrNull()
        if (!originalError.isPermissionError()) return primaryResult

        val backend = backends.firstOrNull { item -> item.isAuthorized() }
            ?: return primaryResult
        val result = backend.withClient(operation)
        if (result.isSuccess) return result
        return primaryResult
    }
}
