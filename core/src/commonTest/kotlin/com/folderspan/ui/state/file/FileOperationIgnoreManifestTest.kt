package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ignore.IgnoreMatcher
import com.folderspan.ignore.IgnorePreferenceScope
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.ui.state.main.TaskRuntimeEntryKind
import com.folderspan.ui.state.main.TaskRuntimeQueueCategory
import com.folderspan.ui.state.main.TaskRuntimeStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileOperationIgnoreManifestTest {
    @Test
    fun copyManifestSeparatesDirectoriesEmptyFilesAndNonEmptyFiles() {
        val source = file("/source", isDirectory = true)
        val destination = file("/target/source", isDirectory = true)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = null,
            children = listOf(
                file("/source/empty-folder", isDirectory = true),
                file("/source/empty-folder/.keep", size = 0L),
                file("/source/content.txt", size = 12L),
            ),
        )

        assertEquals(
            listOf("/source", "/source/empty-folder"),
            plan.entries.directories.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf("/source/empty-folder/.keep"),
            plan.entries.emptyFiles.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf("/source/content.txt"),
            plan.entries.files.map { entry -> entry.src.path },
        )
        assertEquals(
            listOf(TaskRuntimeEntryKind.EMPTY_FILE_CREATE),
            plan.entries.emptyFiles.map { entry -> entry.kind },
        )
        assertEquals(4, plan.entries.totalSize)
        assertEquals(12L, plan.entries.totalFileBytes)
    }

    @Test
    fun copyStageOrdersDirectoryEmptyFileAndFileQueues() {
        assertEquals(
            listOf(
                TaskRuntimeQueueCategory.DIRECTORIES,
                TaskRuntimeQueueCategory.EMPTY_FILES,
                TaskRuntimeQueueCategory.FILES,
            ),
            TaskRuntimeQueueEntriesByCategory().byCategory.keys.toList(),
        )
    }

    @Test
    fun localCopyManifestOmitsIgnoredEntriesAndTotals() {
        val source = file("/source", isDirectory = true)
        val destination = file("/target/source", isDirectory = true)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = matcher(source.path, listOf("build/", "*.tmp")),
            children = listOf(
                file("/source/build", isDirectory = true),
                file("/source/build/generated.bin", size = 100),
                file("/source/src", isDirectory = true),
                file("/source/src/main.kt", size = 7),
                file("/source/cache.tmp", size = 3),
            ),
        )

        assertEquals(listOf("/source", "/source/src"), plan.entries.directories.map { entry -> entry.src.path })
        assertEquals(listOf("/target/source", "/target/source/src"), plan.entries.directories.map { entry -> entry.dest.path })
        assertEquals(listOf("/source/src/main.kt"), plan.entries.files.map { entry -> entry.src.path })
        assertEquals(3, plan.entries.totalSize)
        assertEquals(7L, plan.entries.totalFileBytes)
        assertEquals(3, plan.skippedCount)
    }

    @Test
    fun moveDeleteSourceManifestLeavesIgnoredEntriesAndProtectedParents() {
        val source = file("/source", isDirectory = true)
        val destination = file("/target/source", isDirectory = true)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = matcher(source.path, listOf("build/", "*.tmp")),
            children = listOf(
                file("/source/build", isDirectory = true),
                file("/source/build/generated.bin", size = 100),
                file("/source/src", isDirectory = true),
                file("/source/src/main.kt", size = 7),
                file("/source/src/cache.tmp", size = 3),
            ),
        )

        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(plan, TaskRuntimeStage.DELETE_SOURCE)

        assertEquals(setOf("/source", "/source/src"), plan.protectedSourceDirectories)
        assertEquals(listOf("/source/src/main.kt"), deleteEntries.files.map { entry -> entry.src.path })
        assertTrue(deleteEntries.directories.isEmpty())
        assertEquals(1, deleteEntries.totalSize)
    }

    @Test
    fun selectedIgnoredRootBuildsEmptyCopyAndMoveManifests() {
        val source = file("/source/build", isDirectory = true)
        val destination = file("/target/build", isDirectory = true)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = matcher("/source", listOf("build/")),
            children = listOf(file("/source/build/generated.bin", size = 100)),
        )
        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(plan, TaskRuntimeStage.DELETE_SOURCE)

        assertEquals(0, plan.entries.totalSize)
        assertEquals(0L, plan.entries.totalFileBytes)
        assertEquals(1, plan.skippedCount)
        assertEquals(0, deleteEntries.totalSize)
    }

    @Test
    fun enabledIgnoreFileItselfIsOmittedFromCopyAndMoveManifests() {
        val source = file("/source", isDirectory = true)
        val destination = file("/target/source", isDirectory = true)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = matcher(source.path, lines = emptyList()),
            children = listOf(
                file("/source/.gitignore", size = 703),
                file("/source/src", isDirectory = true),
                file("/source/src/main.kt", size = 7),
            ),
        )
        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(plan, TaskRuntimeStage.DELETE_SOURCE)

        assertEquals(listOf("/source/src/main.kt"), plan.entries.files.map { entry -> entry.src.path })
        assertEquals(7L, plan.entries.totalFileBytes)
        assertEquals(1, plan.skippedCount)
        assertEquals(setOf("/source"), plan.protectedSourceDirectories)
        assertEquals(listOf("/source/src/main.kt"), deleteEntries.files.map { entry -> entry.src.path })
        assertEquals(listOf("/source/src"), deleteEntries.directories.map { entry -> entry.src.path })
    }

    @Test
    fun selectedEnabledIgnoreFileBuildsEmptyCopyAndMoveManifests() {
        val source = file("/source/.gitignore", size = 703)
        val destination = file("/target/.gitignore", size = 703)
        val plan = buildCopyQueueEntriesForOperation(
            src = source,
            dest = destination,
            sourceSeparator = "/",
            ignoreMatcher = matcher("/source", lines = emptyList()),
        )
        val deleteEntries = buildDeleteQueueEntriesFromCopyPlan(plan, TaskRuntimeStage.DELETE_SOURCE)

        assertEquals(0, plan.entries.totalSize)
        assertEquals(0L, plan.entries.totalFileBytes)
        assertEquals(1, plan.skippedCount)
        assertEquals(0, deleteEntries.totalSize)
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
        size: Long = if (isDirectory) 0L else 1L,
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = "",
            size = size,
            createdDate = 1L,
            updatedDate = 1L,
            protocol = FileProtocol.Local,
        )
    }
}
