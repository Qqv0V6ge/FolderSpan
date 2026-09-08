package com.folderspan.ignore

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IgnoreOperationFilterTest {
    @Test
    fun discoversOnlySupportedRegularIgnoreFilesInStableOrder() {
        val result = findExistingSupportedIgnoreFileNames(
            listOf(
                file("/source/.dockerignore"),
                file("/source/.gitignore"),
                file("/source/.npmignore", isDirectory = true),
                file("/source/.eslintignore").withCopy(isSymbolicLink = true),
                file("/source/custom.ignore"),
            )
        )

        assertEquals(listOf(".gitignore", ".dockerignore"), result)
    }

    @Test
    fun filtersIgnoredDirectorySubtreesAndProtectsSourceAncestors() {
        val root = file("/project", isDirectory = true)
        val matcher = matcher(
            basePath = root.path,
            lines = listOf(
                "build/",
                "*.tmp",
            )
        )

        val result = filterIgnoredOperationEntries(
            root = root,
            entries = listOf(
                file("/project/build", isDirectory = true),
                file("/project/build/generated.bin"),
                file("/project/cache.tmp"),
                file("/project/src", isDirectory = true),
                file("/project/src/main.kt"),
            ),
            matcher = matcher,
            separator = "/",
        )

        assertEquals(
            setOf("/project/src", "/project/src/main.kt"),
            result.entries.map { item -> item.path }.toSet(),
        )
        assertEquals(setOf("/project"), result.protectedSourceDirectories)
        assertEquals(3, result.skippedCount)
    }

    @Test
    fun preservesLaterNegationRulesForNonSkippedSubtrees() {
        val root = file("/project", isDirectory = true)
        val matcher = matcher(
            basePath = root.path,
            lines = listOf(
                "*.log",
                "!keep.log",
            )
        )

        val result = filterIgnoredOperationEntries(
            root = root,
            entries = listOf(
                file("/project/error.log"),
                file("/project/keep.log"),
            ),
            matcher = matcher,
            separator = "/",
        )

        assertEquals(listOf("/project/keep.log"), result.entries.map { item -> item.path })
        assertEquals(setOf("/project"), result.protectedSourceDirectories)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun skipsEnabledIgnoreFileItselfDuringOperations() {
        val root = file("/project", isDirectory = true)
        val matcher = matcher(
            basePath = root.path,
            lines = emptyList(),
        )

        val result = filterIgnoredOperationEntries(
            root = root,
            entries = listOf(
                file("/project/.gitignore"),
                file("/project/src", isDirectory = true),
                file("/project/src/main.kt"),
            ),
            matcher = matcher,
            separator = "/",
        )

        assertEquals(
            setOf("/project/src", "/project/src/main.kt"),
            result.entries.map { item -> item.path }.toSet(),
        )
        assertEquals(setOf("/project"), result.protectedSourceDirectories)
        assertEquals(1, result.skippedCount)
        assertTrue(matcher.matchesOperationSkip("/project/.gitignore", isDirectory = false))
        assertFalse(matcher.matchesOperationSkip("/project/src/main.kt", isDirectory = false))
    }

    @Test
    fun detectsExplicitlySelectedIgnoredRoot() {
        val selectedRoot = file("/project/build", isDirectory = true)
        val matcher = matcher(
            basePath = "/project",
            lines = listOf("build/"),
        )

        assertTrue(matcher.matches(selectedRoot.path, selectedRoot.isDirectory))
        assertFalse(matcher.matches("/project/src", isDirectory = true))
    }

    @Test
    fun loadsRulesForRemoteProtocolScopesWithFakeReaders() = runSuspendTest {
        listOf(FileProtocol.Share, FileProtocol.Device, FileProtocol.Network).forEach { protocol ->
            val readPaths = mutableListOf<String>()
            val resolved = loadResolvedIgnoreMatcher(
                preference = IgnorePreferenceScope(
                    protocol = protocol,
                    protocolId = "source-id",
                    basePath = "/source",
                    ignoreFiles = listOf(".gitignore"),
                    separator = "/",
                )
            ) { path ->
                readPaths += path
                Result.success(listOf("ignored.txt"))
            }

            assertEquals(listOf("/source/.gitignore"), readPaths)
            assertTrue(resolved.matches("/source/ignored.txt", isDirectory = false))
            assertFalse(resolved.matches("/source/visible.txt", isDirectory = false))
        }
    }

    @Test
    fun strictLoadingUsesStableSupportedOrderForLocalDeviceAndNetworkSources() = runSuspendTest {
        listOf(FileProtocol.Local, FileProtocol.Device, FileProtocol.Network).forEach { protocol ->
            val readPaths = mutableListOf<String>()
            val resolved = loadResolvedIgnoreMatcherStrict(
                preference = IgnorePreferenceScope(
                    protocol = protocol,
                    protocolId = "source-id",
                    basePath = "/source",
                    ignoreFiles = listOf(".dockerignore", ".gitignore", ".dockerignore"),
                    separator = "/",
                )
            ) { path ->
                readPaths += path
                Result.success(
                    if (path.endsWith(".gitignore")) listOf("*.log") else listOf("!keep.log")
                )
            }.getOrThrow()

            assertEquals(
                listOf("/source/.gitignore", "/source/.dockerignore"),
                readPaths,
            )
            assertTrue(resolved.matchesRelativeOperationSkip("error.log", isDirectory = false))
            assertFalse(resolved.matchesRelativeOperationSkip("keep.log", isDirectory = false))
        }
    }

    @Test
    fun strictLoadingFailsBeforeMutationWhenAnySelectedFileCannotBeRead() = runSuspendTest {
        val readPaths = mutableListOf<String>()
        var targetMutations = 0
        val result = loadResolvedIgnoreMatcherStrict(
            preference = IgnorePreferenceScope(
                protocol = FileProtocol.Network,
                protocolId = "network-id",
                basePath = "/source",
                ignoreFiles = listOf(".gitignore", ".dockerignore"),
                separator = "/",
            )
        ) { path ->
            readPaths += path
            if (path.endsWith(".dockerignore")) {
                Result.failure(IllegalStateException("offline"))
            } else {
                Result.success(listOf("build/"))
            }
        }
        if (result.isSuccess) targetMutations++

        assertTrue(result.isFailure)
        assertEquals(0, targetMutations)
        assertEquals(listOf("/source/.gitignore", "/source/.dockerignore"), readPaths)
        assertEquals(".dockerignore", assertIs<IgnoreFileReadException>(result.exceptionOrNull()).fileName)
    }

    @Test
    fun strictLoadingRefreshesRulesOnEveryResolution() = runSuspendTest {
        var lines = listOf("old.txt")
        val preference = IgnorePreferenceScope(
            protocol = FileProtocol.Local,
            protocolId = "",
            basePath = "/source",
            ignoreFiles = listOf(".gitignore"),
            separator = "/",
        )

        val first = loadResolvedIgnoreMatcherStrict(preference) { Result.success(lines) }.getOrThrow()
        lines = listOf("new.txt")
        val second = loadResolvedIgnoreMatcherStrict(preference) { Result.success(lines) }.getOrThrow()

        assertTrue(first.matchesRelativeOperationSkip("old.txt", isDirectory = false))
        assertFalse(first.matchesRelativeOperationSkip("new.txt", isDirectory = false))
        assertFalse(second.matchesRelativeOperationSkip("old.txt", isDirectory = false))
        assertTrue(second.matchesRelativeOperationSkip("new.txt", isDirectory = false))
    }

    private fun matcher(
        basePath: String,
        lines: List<String>,
    ): ResolvedIgnoreMatcher {
        return ResolvedIgnoreMatcher(
            preference = IgnorePreferenceScope(
                protocol = FileProtocol.Local,
                protocolId = "",
                basePath = basePath,
                ignoreFiles = listOf(".gitignore"),
                separator = "/",
            ),
            matcher = IgnoreMatcher(IgnoreMatcher.parse(lines)),
        )
    }

    private fun file(
        path: String,
        isDirectory: Boolean = false,
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = "",
            size = if (isDirectory) 0L else 1L,
            createdDate = 1L,
            updatedDate = 1L,
        )
    }
}
