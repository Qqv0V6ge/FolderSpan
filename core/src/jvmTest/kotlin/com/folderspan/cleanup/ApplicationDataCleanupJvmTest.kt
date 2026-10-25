package com.folderspan.cleanup

import com.folderspan.runtime.FolderSpanInstanceLock

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationDataCleanupJvmTest {
    @Test
    fun applicationDataDirectoryMatchesDesktopConventions() {
        val home = Path.of("/test-home")

        assertEquals(
            home.resolve(".local/share/FolderSpan").toAbsolutePath().normalize(),
            resolveDesktopApplicationDataDirectory(home, "Linux", null, null),
        )
        assertEquals(
            home.resolve("Library/Application Support/FolderSpan").toAbsolutePath().normalize(),
            resolveDesktopApplicationDataDirectory(home, "Mac OS X", null, "/xdg-data"),
        )
        assertEquals(
            Path.of("C:/Users/test/AppData/Roaming/FolderSpan").toAbsolutePath().normalize(),
            resolveDesktopApplicationDataDirectory(home, "Windows 11", "C:/Users/test/AppData/Roaming", "/xdg-data"),
        )
    }

    @Test
    fun linuxApplicationDataDirectoryUsesXdgDataHomeIncludingFlatpak() {
        val home = Path.of("/test-home").toAbsolutePath().normalize()
        val dataHomes = listOf(
            home.resolve("custom-data"),
            home.resolve(".var/app/com.folderspan.FolderSpan/data"),
        )

        dataHomes.forEach { dataHome ->
            assertEquals(
                dataHome.resolve("FolderSpan"),
                resolveDesktopApplicationDataDirectory(home, "Linux", null, dataHome.toString()),
            )
        }
    }

    @Test
    fun linuxApplicationDataDirectoryIgnoresEmptyAndRelativeXdgDataHome() {
        val home = Path.of("/test-home")

        listOf(null, "", "  ", "relative/data").forEach { dataHome ->
            assertEquals(
                home.resolve(".local/share/FolderSpan").toAbsolutePath().normalize(),
                resolveDesktopApplicationDataDirectory(home, "Linux", null, dataHome),
            )
        }
    }

    @Test
    fun folderSpanPreferenceFilterPreservesUnrelatedKeys() {
        assertTrue(isFolderSpanPreferenceKey("settings.deviceId"))
        assertTrue(isFolderSpanPreferenceKey("auth.accessToken"))
        assertTrue(isFolderSpanPreferenceKey("trustedTlsFp.device-hash"))
        assertFalse(isFolderSpanPreferenceKey("unrelated.preference"))
    }

    @Test
    fun pendingCleanupDeletesOnlyKnownDataAndPreferences() {
        val root = Files.createTempDirectory("folderspan-cleanup-test")
        val preferences = Preferences.userRoot().node("folderspan-cleanup-test-${UUID.randomUUID()}")
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val appData = Files.createDirectories(root.resolve("app-data"))
            val working = Files.createDirectories(root.resolve("working"))
            val cache = Files.createDirectories(root.resolve("cache"))
            Files.writeString(appData.resolve("folderspan.db"), "database")
            Files.createDirectories(appData.resolve("share-history"))
            Files.writeString(appData.resolve("share-history/item.json"), "history")
            Files.createDirectories(working.resolve("share-history"))
            Files.writeString(working.resolve("share-history/keep.txt"), "keep")
            Files.createDirectories(cache.resolve("editor-content"))
            Files.writeString(cache.resolve("editor-content/page.bin"), "cache")
            Files.createDirectories(cache.resolve("unrelated-cache"))
            Files.writeString(cache.resolve("unrelated-cache/keep.txt"), "keep")
            preferences.put("settings.deviceId", "device")
            preferences.put("auth.accessToken", "token")
            preferences.put("unrelated.preference", "keep")
            var loginStartupRemoved = false
            val environment = DesktopCleanupEnvironment(
                homeDirectory = home,
                applicationDataDirectory = appData,
                cacheDirectory = cache,
                preferences = preferences,
                removeLoginStartup = { loginStartupRemoved = true },
            )
            val cleaner = DesktopApplicationDataCleaner()

            cleaner.requestCleanup(
                homeDirectory = home,
                categories = ApplicationDataCleanupCategory.all,
            ).getOrThrow()
            assertTrue(cleaner.completePendingCleanup(environment).getOrThrow())

            assertFalse(Files.exists(appData))
            assertTrue(Files.exists(working.resolve("share-history/keep.txt")))
            assertFalse(Files.exists(cache.resolve("editor-content")))
            assertTrue(Files.exists(cache.resolve("unrelated-cache/keep.txt")))
            assertNull(preferences.get("settings.deviceId", null))
            assertNull(preferences.get("auth.accessToken", null))
            assertEquals("keep", preferences.get("unrelated.preference", null))
            assertTrue(loginStartupRemoved)
            assertFalse(Files.exists(home.resolve(".folderspan-cleanup-pending")))
        } finally {
            runCatching { preferences.removeNode() }
            deleteRecursivelyForTest(root)
        }
    }

    @Test
    fun cleanupRetainsTheOwnedInstanceLock() {
        val root = Files.createTempDirectory("folderspan-cleanup-lock")
        val preferences = Preferences.userRoot().node("folderspan-cleanup-lock-${UUID.randomUUID()}")
        try {
            val appData = Files.createDirectories(root.resolve("app-data"))
            val environment = DesktopCleanupEnvironment(root, appData, root.resolve("cache"), preferences, {})
            val cleaner = DesktopApplicationDataCleaner()
            FolderSpanInstanceLock.tryAcquire(appData)!!.use {
                Files.writeString(appData.resolve("folderspan.db"), "database")
                cleaner.requestCleanup(root, setOf(ApplicationDataCleanupCategory.ApplicationData)).getOrThrow()
                assertTrue(cleaner.completePendingCleanup(environment).getOrThrow())
                assertFalse(Files.exists(appData.resolve("folderspan.db")))
                assertTrue(Files.exists(appData.resolve(FolderSpanInstanceLock.FILE_NAME)))
                assertNull(FolderSpanInstanceLock.tryAcquire(appData))
            }
            FolderSpanInstanceLock.tryAcquire(appData)!!.close()
        } finally {
            preferences.removeNode()
            deleteRecursivelyForTest(root)
        }
    }

    @Test
    fun pendingCleanupHonorsSelectedCategories() {
        val root = Files.createTempDirectory("folderspan-selective-cleanup-test")
        val preferences = Preferences.userRoot().node("folderspan-selective-cleanup-test-${UUID.randomUUID()}")
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val appData = Files.createDirectories(root.resolve("app-data"))
            val cache = Files.createDirectories(root.resolve("cache"))
            Files.writeString(appData.resolve("folderspan.db"), "database")
            Files.createDirectories(appData.resolve("secure-settings"))
            Files.writeString(appData.resolve("secure-settings/values.v1"), "vault")
            Files.createDirectories(appData.resolve("share-history"))
            Files.writeString(appData.resolve("share-history/item.json"), "history")
            Files.createDirectories(appData.resolve("sync-tasks"))
            Files.writeString(appData.resolve("sync-tasks/item.pb64"), "task")
            Files.createDirectories(cache.resolve("editor-content"))
            Files.writeString(cache.resolve("editor-content/page.bin"), "cache")
            preferences.put("settings.deviceId", "device")
            var loginStartupRemoved = false
            val environment = DesktopCleanupEnvironment(
                homeDirectory = home,
                applicationDataDirectory = appData,
                cacheDirectory = cache,
                preferences = preferences,
                removeLoginStartup = { loginStartupRemoved = true },
            )
            val cleaner = DesktopApplicationDataCleaner()

            cleaner.requestCleanup(
                homeDirectory = home,
                categories = setOf(
                    ApplicationDataCleanupCategory.PreferencesAndAccount,
                    ApplicationDataCleanupCategory.TransferHistory,
                ),
            ).getOrThrow()
            assertTrue(cleaner.completePendingCleanup(environment).getOrThrow())

            assertTrue(Files.exists(appData.resolve("folderspan.db")))
            assertFalse(Files.exists(appData.resolve("secure-settings")))
            assertFalse(Files.exists(appData.resolve("share-history")))
            assertFalse(Files.exists(appData.resolve("sync-tasks")))
            assertTrue(Files.exists(cache.resolve("editor-content/page.bin")))
            assertNull(preferences.get("settings.deviceId", null))
            assertFalse(loginStartupRemoved)
        } finally {
            runCatching { preferences.removeNode() }
            deleteRecursivelyForTest(root)
        }
    }

    @Test
    fun cleanupRequestRejectsEmptySelection() {
        val root = Files.createTempDirectory("folderspan-empty-cleanup-test")
        try {
            val result = DesktopApplicationDataCleaner().requestCleanup(
                homeDirectory = root,
                categories = emptySet(),
            )

            assertTrue(result.isFailure)
            assertFalse(Files.exists(root.resolve(".folderspan-cleanup-pending")))
        } finally {
            deleteRecursivelyForTest(root)
        }
    }
}

private fun deleteRecursivelyForTest(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
