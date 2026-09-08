package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncStateIgnoreDiscoveryJvmTest : ChineseLocalizationTest() {
    private val taskStore = object : SyncTaskStore {
        override fun loadTasks(): List<SyncTask> = emptyList()

        override fun saveTask(task: SyncTask): SyncTask = task.copy(id = task.id.coerceAtLeast(1L))

        override fun deleteTask(taskId: Long) = Unit

        override fun appendRun(record: SyncRunRecord) = Unit

        override fun loadRuns(taskId: Long): List<SyncRunRecord> = emptyList()

        override fun deleteRuns(taskId: Long) = Unit
    }

    @Test
    fun discoveryAndSaveValidationRequireSupportedRegularFilesInSourceRoot() = runBlocking {
        val source = Files.createTempDirectory("sync-ignore-source")
        val target = Files.createTempDirectory("sync-ignore-target")
        Files.createFile(source.resolve(".gitignore"))
        Files.createDirectory(source.resolve(".dockerignore"))
        Files.createFile(source.resolve("custom.ignore"))
        val syncState = SyncState(taskStore)

        val discovered = syncState.discoverSourceIgnoreFileNames(
            sourceType = SyncEndpointType.Local,
            sourceRef = "",
            sourcePath = source.absolutePathString(),
        ).getOrThrow()

        assertEquals(listOf(".gitignore"), discovered)

        val saveResult = syncState.createOrUpdateTask(
            SyncTask(
                id = 0,
                name = "missing Ignore file",
                sourceType = SyncEndpointType.Local,
                sourcePath = source.absolutePathString(),
                targetType = SyncEndpointType.Local,
                targetPath = target.absolutePathString(),
                useIgnoreFiles = true,
                ignoreFileNames = listOf(".dockerignore"),
            )
        )

        assertTrue(saveResult.isFailure)
        assertEquals(
            AppStrings.ui_test_sync_state_ignore_discovery_jvm_source_directory_does_not_contain,
            saveResult.exceptionOrNull()?.message,
        )
    }
}
