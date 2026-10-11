package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncStateTest : ChineseLocalizationTest() {
    private val emptyTaskStore = object : SyncTaskStore {
        override fun loadTasks(): List<SyncTask> = emptyList()

        override fun saveTask(task: SyncTask): SyncTask = task

        override fun deleteTask(taskId: Long) = Unit

        override fun appendRun(record: SyncRunRecord) = Unit

        override fun loadRuns(taskId: Long): List<SyncRunRecord> = emptyList()

        override fun deleteRuns(taskId: Long) = Unit
    }

    @Test
    fun parseSyncFilterPatternsSplitsAndTrims() {
        val patterns = parseSyncFilterPatterns("*.tmp, *.log;cache/*\n  .DS_Store  ")
        assertEquals(listOf("*.tmp", "*.log", "cache/*", ".DS_Store"), patterns)
    }

    @Test
    fun validateTaskRejectsSameLocalSourceAndTargetPath() {
        val syncState = SyncState(emptyTaskStore)

        val error = syncState.validateTask(
            name = "Local mirror",
            sourceType = SyncEndpointType.Local,
            sourceRef = "",
            sourcePath = "/remote/source",
            targetType = SyncEndpointType.Local,
            targetRef = "",
            targetPath = "/remote/source",
        )

        assertEquals(AppStrings.ui_source_target_cannot_same, error)
    }

    @Test
    fun syncTargetEndpointReadinessErrorRequiresRemoteTargetToExistOnlineAndConnect() {
        assertEquals(
            null,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Local,
                exists = false,
                online = false,
                connected = false,
            )
        )
        assertEquals(
            AppStrings.ui_target_device_does_not_exist,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Device,
                exists = false,
                online = false,
                connected = false,
            )
        )
        assertEquals(
            AppStrings.ui_test_sync_state_target_device_is_offline,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Device,
                exists = true,
                online = false,
                connected = false,
            )
        )
        assertEquals(
            AppStrings.webrtc_target_device_not_connected,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Device,
                exists = true,
                online = true,
                connected = false,
            )
        )
        assertEquals(
            AppStrings.ui_target_network_disk_does_not_exist,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Network,
                exists = false,
                online = false,
                connected = false,
            )
        )
        assertEquals(
            AppStrings.ui_test_sync_state_target_network_drive_not_connected,
            syncTargetEndpointReadinessError(
                type = SyncEndpointType.Network,
                exists = true,
                online = true,
                connected = false,
            )
        )
    }

    @Test
    fun syncTargetEndpointMissingErrorOnlyRejectsDeletedOrMissingTargets() {
        assertEquals(null, syncTargetEndpointMissingError(SyncEndpointType.Local, exists = false))
        assertEquals(null, syncTargetEndpointMissingError(SyncEndpointType.Device, exists = true))
        assertEquals(null, syncTargetEndpointMissingError(SyncEndpointType.Network, exists = true))
        assertEquals(AppStrings.ui_target_device_does_not_exist, syncTargetEndpointMissingError(SyncEndpointType.Device, exists = false))
        assertEquals(AppStrings.ui_target_network_disk_does_not_exist, syncTargetEndpointMissingError(SyncEndpointType.Network, exists = false))
    }

    @Test
    fun syncSkippedMessageWrapsEndpointReadinessReason() {
        assertEquals(AppStrings.ui_test_sync_state_target_device_not_connected, syncSkippedMessage(AppStrings.webrtc_target_device_not_connected))
    }

    @Test
    fun syncFilterMatchesSupportsNameAndRelativePath() {
        val patterns = listOf("*.tmp", "cache/*")

        assertTrue(syncFilterMatches("cache/a.txt", "a.txt", patterns))
        assertTrue(syncFilterMatches("foo/bar.tmp", "bar.tmp", patterns))
        assertFalse(syncFilterMatches("docs/readme.md", "readme.md", patterns))
    }

    @Test
    fun syncFileChangedUsesSizeAndMtime() {
        val source = FileSimpleInfo.pathFileSimpleInfo("/a.txt").withCopy(
            isDirectory = false,
            name = "a.txt",
            size = 100,
            updatedDate = 200,
        )
        val sameTarget = source.withCopy(path = "/b.txt")
        val largerTarget = source.withCopy(path = "/b.txt", size = 120)
        val newerTarget = source.withCopy(path = "/b.txt", updatedDate = 300)

        assertFalse(syncFileChanged(source, sameTarget))
        assertTrue(syncFileChanged(source, largerTarget))
        assertFalse(syncFileChanged(source, newerTarget))
        assertTrue(syncFileChanged(newerTarget, source))
    }

    @Test
    fun buildSyncRenamePathFindsNextAvailableIndex() {
        val occupied = setOf(
            "/target/a.txt",
            "/target/a(1).txt",
            "/target/a(2).txt",
        )

        val renamed = buildSyncRenamePath(
            path = "/target/a.txt",
            isDirectory = false,
            occupied = occupied,
            separator = "/",
        )

        assertEquals("/target/a(3).txt", renamed)
    }

    @Test
    fun syncTargetInsideSourcePathDetectsNestedPath() {
        assertTrue(
            syncTargetInsideSourcePath(
                sourcePath = "/remote/source",
                targetPath = "/remote/source/backup",
                separator = "/",
            )
        )
    }

    @Test
    fun syncTargetInsideSourcePathIgnoresSiblingPath() {
        assertFalse(
            syncTargetInsideSourcePath(
                sourcePath = "/remote/source",
                targetPath = "/remote/source-copy",
                separator = "/",
            )
        )
    }
}
