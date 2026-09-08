package com.folderspan.privileged

import com.folderspan.root.RootManager
import com.folderspan.shizuku.ShizukuManager

object ShizukuPrivilegedFileBackend : PrivilegedFileBackend {
    override fun isAuthorized(): Boolean = ShizukuManager.hasPermission()

    override suspend fun awaitReady(): Boolean = ShizukuManager.awaitServiceReady()

    override fun <T> withClient(operation: (PrivilegedFileClient) -> Result<T>): Result<T> =
        ShizukuManager.withClient { client -> operation(client) }
}

object RootPrivilegedFileBackend : PrivilegedFileBackend {
    override fun isAuthorized(): Boolean = RootManager.hasPermission()

    override fun usesBeforeLocalIo(): Boolean = RootManager.hasPermission()

    override suspend fun awaitReady(): Boolean = RootManager.awaitServiceReady()

    override fun <T> withClient(operation: (PrivilegedFileClient) -> Result<T>): Result<T> =
        RootManager.withClient(operation)
}

object PrivilegedFileBackends {
    fun defaults(): List<PrivilegedFileBackend> =
        listOf(RootPrivilegedFileBackend, ShizukuPrivilegedFileBackend)

    fun preferredAuthorized(): PrivilegedFileBackend? =
        defaults().firstOrNull { backend -> backend.isAuthorized() }

    fun beforeLocalIo(): PrivilegedFileBackend? =
        defaults().firstOrNull { backend -> backend.usesBeforeLocalIo() && backend.isAuthorized() }
}
