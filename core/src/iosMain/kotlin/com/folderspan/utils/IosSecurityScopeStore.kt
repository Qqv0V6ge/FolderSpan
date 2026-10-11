package com.folderspan.utils

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import strings.AppStrings

/**
 * Stores security-scoped URLs (typically from the Files app) keyed by file system path.
 * This allows iOS file operations to temporarily start/stop security access on demand,
 * without copying the file into the app sandbox.
 */
object IosSecurityScopeStore {
    private val lock = NSLock()
    private val urlsByPath = linkedMapOf<String, NSURL>()

    fun register(url: NSURL) {
        val path = url.path?.trim().orEmpty()
        if (path.isEmpty()) return
        lock.lock()
        try {
            urlsByPath.remove(path)
            urlsByPath[path] = url
        } finally {
            lock.unlock()
        }
    }

    fun resolve(path: String): NSURL? {
        val key = path.trim()
        if (key.isEmpty()) return null
        lock.lock()
        return try {
            urlsByPath[key]
        } finally {
            lock.unlock()
        }
    }

    fun registeredPaths(): List<String> {
        lock.lock()
        return try {
            urlsByPath.keys.toList()
        } finally {
            lock.unlock()
        }
    }

    fun unregister(paths: Iterable<String>) {
        val pathSet = paths.map { item -> item.trim() }.filter { item -> item.isNotEmpty() }.toSet()
        if (pathSet.isEmpty()) return
        lock.lock()
        try {
            pathSet.forEach(urlsByPath::remove)
        } finally {
            lock.unlock()
        }
    }

    fun hasRegisteredPaths(): Boolean {
        lock.lock()
        return try {
            urlsByPath.isNotEmpty()
        } finally {
            lock.unlock()
        }
    }

    inline fun <T> withSecurityScopeIfNeeded(path: String, block: () -> T): T {
        val url = resolve(path) ?: return block()
        val accessed = url.startAccessingSecurityScopedResource()
        return try {
            block()
        } finally {
            if (accessed) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    suspend inline fun <T> withSuspendSecurityScopeIfNeeded(
        path: String,
        crossinline block: suspend () -> T,
    ): T {
        val url = resolve(path) ?: return block()
        val accessed = url.startAccessingSecurityScopedResource()
        return try {
            block()
        } finally {
            if (accessed) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    /**
     * Coordinates reads for URLs received from document providers before touching their contents.
     * iCloud Drive and third-party providers may expose a zero-byte placeholder until a coordinated
     * read materializes the actual file.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    inline fun <T> withCoordinatedReadIfNeeded(
        path: String,
        crossinline block: (coordinatedPath: String) -> T,
    ): T {
        val normalizedPath = path.trim()
        val url = resolve(normalizedPath) ?: return block(normalizedPath)
        val accessed = url.startAccessingSecurityScopedResource()

        return try {
            val coordinator = NSFileCoordinator(filePresenter = null)
            var blockResult: Result<T>? = null

            memScoped {
                val coordinationError = alloc<ObjCObjectVar<NSError?>>()
                coordinationError.value = null
                coordinator.coordinateReadingItemAtURL(
                    url = url,
                    options = NSFileCoordinatorReadingWithoutChanges,
                    error = coordinationError.ptr,
                ) { coordinatedUrl ->
                    val coordinatedPath = coordinatedUrl?.path?.trim().orEmpty()
                    blockResult = runCatching {
                        block(coordinatedPath.ifEmpty { normalizedPath })
                    }
                }

                val result = blockResult
                if (result != null) {
                    result.getOrThrow()
                } else if (
                    !accessed ||
                    (
                        NSFileManager.defaultManager.fileExistsAtPath(normalizedPath) &&
                            NSFileManager.defaultManager.isReadableFileAtPath(normalizedPath)
                    )
                ) {
                    // Plain local URLs and app-group files can be directly readable even when a
                    // coordinator refuses the URL. Keep those files visible instead of dropping
                    // them from open://.
                    block(normalizedPath)
                } else {
                    throw IllegalStateException(
                        coordinationError.value?.localizedDescription
                            ?: AppStrings.ui_unable_to_coordinate_reading_external_files
                    )
                }
            }
        } finally {
            if (accessed) {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }
}
