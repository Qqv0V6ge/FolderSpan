package com.folderspan.cleanup

import android.content.Context
import com.folderspan.androidContext
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore

private const val CLEANUP_REQUEST_FILE = ".folderspan-cleanup-pending"
private const val DATABASE_NAME = "folderspan.db"
private const val TINK_PREFERENCES_NAME = "tink_prefs"
private const val TINK_MASTER_KEY_ALIAS = "tink_master_key"

private val ANDROID_AVAILABLE_CATEGORIES = setOf(
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
        ANDROID_AVAILABLE_CATEGORIES

    actual val isSupported: Boolean = true

    actual suspend fun requestCleanup(
        categories: Set<ApplicationDataCleanupCategory>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireAvailableApplicationDataCleanupCategories(categories, availableCategories)
            cleanupRequestFile(androidContext()).apply {
                parentFile?.mkdirs()
                writeText(encodeApplicationDataCleanupRequest(categories))
            }
            Unit
        }
    }

    actual fun completePendingCleanup(): Result<Boolean> = runCatching {
        val context = androidContext()
        val marker = cleanupRequestFile(context)
        if (!marker.exists()) return@runCatching false

        val categories = decodeApplicationDataCleanupRequest(marker.readText())
        requireAvailableApplicationDataCleanupCategories(categories, availableCategories)

        if (ApplicationDataCleanupCategory.ApplicationData in categories) {
            deleteDatabase(context)
            deleteRecursively(File(context.filesDir, "tls-identity"))
        }
        if (ApplicationDataCleanupCategory.PreferencesAndAccount in categories) {
            deleteRecursively(File(context.filesDir, "datastore"))
            check(
                context.getSharedPreferences(TINK_PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            ) { "Unable to clear Android encryption preferences" }
            context.deleteSharedPreferences(TINK_PREFERENCES_NAME)
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    if (containsAlias(TINK_MASTER_KEY_ALIAS)) {
                        deleteEntry(TINK_MASTER_KEY_ALIAS)
                    }
                }
            }.getOrThrow()
        }
        if (ApplicationDataCleanupCategory.TransferHistory in categories) {
            APPLICATION_DIRECTORY_NAMES.forEach { name ->
                deleteRecursively(File(context.filesDir, name))
            }
        }
        if (ApplicationDataCleanupCategory.CacheAndLogs in categories) {
            APPLICATION_CACHE_DIRECTORY_NAMES.forEach { name ->
                deleteRecursively(File(context.cacheDir, name))
            }
        }

        check(marker.delete() || !marker.exists()) { "Unable to remove the Android cleanup request" }
        true
    }
}

private fun cleanupRequestFile(context: Context): File =
    File(context.noBackupFilesDir, CLEANUP_REQUEST_FILE)

private fun deleteDatabase(context: Context) {
    val database = context.getDatabasePath(DATABASE_NAME)
    if (!database.exists()) return
    check(context.deleteDatabase(DATABASE_NAME) || !database.exists()) {
        "Unable to delete the FolderSpan database"
    }
}

private fun deleteRecursively(file: File) {
    if (!file.exists() && !PathUtils.isSymbolicLink(FileAccessPermission.Allowed, file.absolutePath)) return
    PathUtils.deleteDirectory(FileAccessPermission.Allowed, file.absolutePath)
    check(!file.exists() && !PathUtils.isSymbolicLink(FileAccessPermission.Allowed, file.absolutePath)) {
        "Unable to delete ${file.absolutePath}"
    }
}
