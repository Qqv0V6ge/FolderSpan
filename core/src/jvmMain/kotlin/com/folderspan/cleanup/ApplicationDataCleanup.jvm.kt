package com.folderspan.cleanup

import com.folderspan.permission.DesktopLoginStartupManager
import com.folderspan.settings.DESKTOP_SECURE_SETTINGS_DIRECTORY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.prefs.Preferences

private const val CLEANUP_REQUEST_FILE = ".folderspan-cleanup-pending"

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
    private val cleaner = DesktopApplicationDataCleaner()

    actual val availableCategories: Set<ApplicationDataCleanupCategory>
        get() = if (desktopOsFamily() == DesktopOsFamily.Unsupported) {
            emptySet()
        } else {
            ApplicationDataCleanupCategory.all
        }

    actual val isSupported: Boolean
        get() = availableCategories.isNotEmpty()

    actual suspend fun requestCleanup(
        categories: Set<ApplicationDataCleanupCategory>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupported) {
            return@withContext Result.failure(
                UnsupportedOperationException("Application data cleanup is not supported on this desktop platform")
            )
        }
        requireAvailableApplicationDataCleanupCategories(categories, availableCategories)
        cleaner.requestCleanup(
            homeDirectory = defaultDesktopCleanupEnvironment().homeDirectory,
            categories = categories,
        )
    }

    actual fun completePendingCleanup(): Result<Boolean> =
        cleaner.completePendingCleanup(defaultDesktopCleanupEnvironment())
}

internal data class DesktopCleanupEnvironment(
    val homeDirectory: Path,
    val applicationDataDirectory: Path,
    val workingDirectory: Path,
    val cacheDirectory: Path,
    val preferences: Preferences,
    val removeLoginStartup: () -> Unit,
)

internal class DesktopApplicationDataCleaner {
    fun requestCleanup(
        homeDirectory: Path,
        categories: Set<ApplicationDataCleanupCategory>,
    ): Result<Unit> = runCatching {
        require(categories.isNotEmpty()) { "At least one application data category must be selected" }
        val marker = cleanupRequestPath(homeDirectory)
        marker.parent?.let(Files::createDirectories)
        Files.writeString(
            marker,
            encodeApplicationDataCleanupRequest(categories),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun completePendingCleanup(environment: DesktopCleanupEnvironment): Result<Boolean> = runCatching {
        val marker = cleanupRequestPath(environment.homeDirectory)
        if (!Files.exists(marker)) return@runCatching false

        val categories = decodeApplicationDataCleanupRequest(Files.readString(marker))
        cleanupTargets(environment, categories).forEach(::deleteRecursively)
        if (ApplicationDataCleanupCategory.PreferencesAndAccount in categories) {
            clearFolderSpanPreferences(environment.preferences)
            deleteRecursively(
                environment.applicationDataDirectory.resolve(DESKTOP_SECURE_SETTINGS_DIRECTORY),
            )
        }
        if (ApplicationDataCleanupCategory.LoginStartup in categories) {
            runCatching(environment.removeLoginStartup)
        }
        Files.deleteIfExists(marker)
        true
    }

    internal fun cleanupTargets(
        environment: DesktopCleanupEnvironment,
        categories: Set<ApplicationDataCleanupCategory>,
    ): List<Path> =
        buildList {
            if (ApplicationDataCleanupCategory.ApplicationData in categories) {
                add(environment.applicationDataDirectory)
            }
            if (ApplicationDataCleanupCategory.TransferHistory in categories) {
                APPLICATION_DIRECTORY_NAMES.forEach { name ->
                    add(environment.workingDirectory.resolve(name))
                }
            }
            if (ApplicationDataCleanupCategory.CacheAndLogs in categories) {
                APPLICATION_CACHE_DIRECTORY_NAMES.forEach { name ->
                    add(environment.cacheDirectory.resolve(name))
                }
                add(environment.applicationDataDirectory.resolve("pro_http_cache"))
            }
        }.map { path -> path.toAbsolutePath().normalize() }.distinct()
}

fun resolveDesktopApplicationDataDirectory(
    homeDirectory: Path = Paths.get(System.getProperty("user.home")),
    osName: String = System.getProperty("os.name").orEmpty(),
    appDataEnvironment: String? = System.getenv("APPDATA"),
): Path = when (desktopOsFamily(osName)) {
    DesktopOsFamily.MacOs -> homeDirectory.resolve("Library/Application Support/FolderSpan")
    DesktopOsFamily.Windows -> Paths.get(appDataEnvironment ?: homeDirectory.toString()).resolve("FolderSpan")
    DesktopOsFamily.Linux,
    DesktopOsFamily.Unsupported -> homeDirectory.resolve(".local/share/FolderSpan")
}.toAbsolutePath().normalize()

private fun defaultDesktopCleanupEnvironment(): DesktopCleanupEnvironment {
    val homeDirectory = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    return DesktopCleanupEnvironment(
        homeDirectory = homeDirectory,
        applicationDataDirectory = resolveDesktopApplicationDataDirectory(homeDirectory),
        workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
        cacheDirectory = resolveDesktopApplicationDataDirectory().resolve("cache"),
        preferences = Preferences.userRoot(),
        removeLoginStartup = {
            check(DesktopLoginStartupManager.disable()) {
                "Unable to remove the FolderSpan login startup item"
            }
        },
    )
}

private fun cleanupRequestPath(homeDirectory: Path): Path =
    homeDirectory.toAbsolutePath().normalize().resolve(CLEANUP_REQUEST_FILE)

private fun clearFolderSpanPreferences(preferences: Preferences) {
    preferences.keys()
        .filter(::isFolderSpanPreferenceKey)
        .forEach(preferences::remove)
    preferences.flush()
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private enum class DesktopOsFamily {
    Windows,
    MacOs,
    Linux,
    Unsupported,
}

private fun desktopOsFamily(osName: String = System.getProperty("os.name").orEmpty()): DesktopOsFamily {
    val normalized = osName.lowercase(Locale.US)
    return when {
        normalized.contains("win") -> DesktopOsFamily.Windows
        normalized.contains("mac") || normalized.contains("darwin") -> DesktopOsFamily.MacOs
        normalized.contains("linux") -> DesktopOsFamily.Linux
        else -> DesktopOsFamily.Unsupported
    }
}
