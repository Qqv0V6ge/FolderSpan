package com.folderspan.cleanup

import com.folderspan.utils.WebFileSystemApiStore
import com.folderspan.utils.WebInMemoryFileStore
import kotlinx.browser.window

private val WEB_AVAILABLE_CATEGORIES = setOf(
    ApplicationDataCleanupCategory.ApplicationData,
    ApplicationDataCleanupCategory.PreferencesAndAccount,
    ApplicationDataCleanupCategory.TransferHistory,
    ApplicationDataCleanupCategory.CacheAndLogs,
)

private val APPLICATION_PATHS = listOf(
    "/share-history",
    "/sync-tasks",
)

private val CACHE_PATHS = listOf(
    "/tmp/scanner_server_cache",
    "/tmp/device_logs",
    "/tmp/file-editor-recovery",
    "/tmp/file-editor-backups",
    "/tmp/file-editor-line-index",
    "/tmp/task-runtime",
    "/tmp/task-failure-results",
    "/tmp/sync-stage",
    "/tmp/editor-content",
    "/tmp/pro_http_cache",
)

actual object ApplicationDataCleanup {
    actual val availableCategories: Set<ApplicationDataCleanupCategory> =
        WEB_AVAILABLE_CATEGORIES

    actual val isSupported: Boolean = true

    actual suspend fun requestCleanup(
        categories: Set<ApplicationDataCleanupCategory>
    ): Result<Unit> = runCatching {
        requireAvailableApplicationDataCleanupCategories(categories, availableCategories)

        if (ApplicationDataCleanupCategory.PreferencesAndAccount in categories) {
            clearFolderSpanWebPreferences()
        }
        if (ApplicationDataCleanupCategory.TransferHistory in categories) {
            APPLICATION_PATHS.forEach { path -> clearWebPath(path) }
        }
        if (ApplicationDataCleanupCategory.CacheAndLogs in categories) {
            CACHE_PATHS.forEach { path -> clearWebPath(path) }
        }
        // Web 数据库只存在于 SQL.js Worker 内存中，由成功后的页面重新加载释放。
    }

    actual fun completePendingCleanup(): Result<Boolean> = Result.success(false)
}

private fun clearFolderSpanWebPreferences() {
    val storage = window.localStorage
    val keys = (0 until storage.length).mapNotNull(storage::key)
    keys.filter(::isFolderSpanPreferenceKey).forEach(storage::removeItem)
}

private suspend fun clearWebPath(path: String) {
    WebInMemoryFileStore.deleteDirectory(path).getOrThrow()
    WebFileSystemApiStore.deleteAndAwait(path)
}
