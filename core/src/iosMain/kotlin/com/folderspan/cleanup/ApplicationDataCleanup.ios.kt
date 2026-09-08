package com.folderspan.cleanup

import com.folderspan.settings.IosKeychainStore
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSUserDefaults

private const val CLEANUP_REQUEST_FILE = ".folderspan-cleanup-pending"
private const val DATABASE_NAME = "folderspan.db"
private const val APP_GROUP_IDENTIFIER = "group.com.folderspan.FolderSpan"
private const val APP_GROUP_LANGUAGE_KEY = "settings.appearance.language"

private val IOS_AVAILABLE_CATEGORIES = setOf(
    ApplicationDataCleanupCategory.ApplicationData,
    ApplicationDataCleanupCategory.PreferencesAndAccount,
    ApplicationDataCleanupCategory.TransferHistory,
    ApplicationDataCleanupCategory.CacheAndLogs,
)

private val APPLICATION_DIRECTORY_NAMES = listOf(
    "share-history",
    "sync-tasks",
)

private val APPLICATION_CACHE_DIRECTORY_NAMES = listOf(
    "scanner_server_cache",
    "device_logs",
    "file-editor-recovery",
    "file-editor-backups",
    "file-editor-line-index",
    "task-runtime",
    "task-failure-results",
    "sync-stage",
    "editor-content",
    "pro_http_cache",
)

actual object ApplicationDataCleanup {
    actual val availableCategories: Set<ApplicationDataCleanupCategory> =
        IOS_AVAILABLE_CATEGORIES

    actual val isSupported: Boolean = true

    actual suspend fun requestCleanup(
        categories: Set<ApplicationDataCleanupCategory>
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            requireAvailableApplicationDataCleanupCategories(categories, availableCategories)
            val marker = cleanupRequestPath()
            marker.parent?.let { parent -> FileSystem.SYSTEM.createDirectories(parent) }
            FileSystem.SYSTEM.sink(marker).buffer().use { sink ->
                sink.writeUtf8(encodeApplicationDataCleanupRequest(categories))
            }
            Unit
        }
    }

    actual fun completePendingCleanup(): Result<Boolean> = runCatching {
        val marker = cleanupRequestPath()
        val fileSystem = FileSystem.SYSTEM
        if (!fileSystem.exists(marker)) return@runCatching false

        val categories = fileSystem.source(marker).buffer().use { source -> source.readUtf8() }
            .let(::decodeApplicationDataCleanupRequest)
        requireAvailableApplicationDataCleanupCategories(categories, availableCategories)

        val appPath = PathUtils.getAppPath().trimEnd('/')
        val cachePath = PathUtils.getCachePath().trimEnd('/')
        if (ApplicationDataCleanupCategory.ApplicationData in categories) {
            listOf(
                "$appPath/$DATABASE_NAME",
                "$appPath/$DATABASE_NAME-wal",
                "$appPath/$DATABASE_NAME-shm",
                "$appPath/$DATABASE_NAME-journal",
                "$appPath/Library/Application Support/FolderSpan/tls-identity",
            ).forEach(::deleteRecursively)
        }
        if (ApplicationDataCleanupCategory.PreferencesAndAccount in categories) {
            NSUserDefaultsSettings.Factory().create("FolderSpan").clear()
            IosKeychainStore.clear()
            NSUserDefaults(suiteName = APP_GROUP_IDENTIFIER)
                .removeObjectForKey(APP_GROUP_LANGUAGE_KEY)
        }
        if (ApplicationDataCleanupCategory.TransferHistory in categories) {
            APPLICATION_DIRECTORY_NAMES.forEach { name ->
                deleteRecursively("$appPath/$name")
            }
        }
        if (ApplicationDataCleanupCategory.CacheAndLogs in categories) {
            APPLICATION_CACHE_DIRECTORY_NAMES.forEach { name ->
                deleteRecursively("$cachePath/$name")
            }
        }

        fileSystem.delete(marker, mustExist = false)
        true
    }
}

private fun cleanupRequestPath(): Path =
    "${PathUtils.getAppPath().trimEnd('/')}/Library/$CLEANUP_REQUEST_FILE".toPath()

private fun deleteRecursively(path: String) {
    if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return
    PathUtils.deleteDirectory(FileAccessPermission.Allowed, path)
}
