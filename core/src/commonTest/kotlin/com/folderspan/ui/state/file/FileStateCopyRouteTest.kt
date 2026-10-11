package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.main.TaskProgressRateSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileStateCopyRouteTest : ChineseLocalizationTest() {

    @Test
    fun remoteShareToLocalUsesShareRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Share,
            destProtocol = FileProtocol.Local,
            srcProtocolId = "remote-share-id"
        )

        assertEquals(CopyRoute.ShareToLocal, route)
    }

    @Test
    fun networkToLocalUsesNetworkRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Network,
            destProtocol = FileProtocol.Local,
            srcProtocolId = "smb://test"
        )

        assertEquals(CopyRoute.NetworkToLocal, route)
    }

    @Test
    fun networkToDeviceUsesStagingRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Network,
            destProtocol = FileProtocol.Device,
            srcProtocolId = "smb://test"
        )

        assertEquals(CopyRoute.NetworkToDevice, route)
    }

    @Test
    fun deviceToNetworkUsesStagingRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Device,
            destProtocol = FileProtocol.Network,
            srcProtocolId = "device-id"
        )

        assertEquals(CopyRoute.DeviceToNetwork, route)
    }

    @Test
    fun remoteShareToNetworkUsesShareToNetworkRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Share,
            destProtocol = FileProtocol.Network,
            srcProtocolId = "remote-share-id"
        )

        assertEquals(CopyRoute.ShareToNetwork, route)
    }

    @Test
    fun systemShareToNetworkUsesShareToNetworkRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Share,
            destProtocol = FileProtocol.Network,
            srcProtocolId = SYSTEM_SHARE_DESK_ID
        )

        assertEquals(CopyRoute.ShareToNetwork, route)
    }

    @Test
    fun networkToNetworkUsesNetworkRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Network,
            destProtocol = FileProtocol.Network,
            srcProtocolId = "smb://source"
        )

        assertEquals(CopyRoute.NetworkToNetwork, route)
    }

    @Test
    fun matchingNonBlankNetworkIdsUseSameEndpointRoute() {
        assertTrue(isSameNetworkEndpoint("network-1", "network-1"))
    }

    @Test
    fun differentOrBlankNetworkIdsDoNotUseSameEndpointRoute() {
        assertFalse(isSameNetworkEndpoint("network-1", "network-2"))
        assertFalse(isSameNetworkEndpoint("", ""))
    }

    @Test
    fun systemShareToLocalUsesSystemShareRoute() {
        val route = resolveCopyRoute(
            srcProtocol = FileProtocol.Share,
            destProtocol = FileProtocol.Local,
            srcProtocolId = SYSTEM_SHARE_DESK_ID
        )

        assertEquals(CopyRoute.SystemShareToLocal, route)
    }

    @Test
    fun browserWebRtcMultiZipPlanUsesSingleArchiveAndUniqueRootNames() {
        val plan = buildWebRtcBrowserMultiZipPlan(
            sources = listOf(
                file("/remote/a.txt", "a.txt"),
                file("/other/a.txt", "a.txt"),
            ),
        )

        assertEquals("downloads.zip", plan.fileName)
        assertEquals(listOf("a.txt", "a(2).txt"), plan.roots.map { root -> root.zipRootName })
    }

    @Test
    fun webRtcTransferProgressTextIncludesSpeedAndRemainingTime() {
        val text = buildWebRtcTransferProgressText(
            transferredBytes = 512L * 1024L,
            totalBytes = 1024L * 1024L,
            chunkSize = 256 * 1024,
            rateSample = TaskProgressRateSample(
                speedPerSecond = 512.0 * 1024.0,
                etaMs = 1_000L,
            ),
        )

        assertEquals(AppStrings.ui_test_file_state_copy_route_transmission_progress_50_0_2_4_speed_512_0_kb_s, text)
    }

    @Test
    fun webRtcZipTotalBytesIgnoresDirectoriesAndNegativeSizes() {
        val files = listOf(
            file("/remote/a.txt", "a.txt").withCopy(size = 512L),
            file("/remote/bad.bin", "bad.bin").withCopy(size = -1L),
            file("/remote/folder", "folder").withCopy(isDirectory = true, size = 4_096L),
        )

        assertEquals(512L, webRtcZipTotalFileBytes(files))
    }

    private fun file(path: String, name: String): FileSimpleInfo =
        FileSimpleInfo(
            name = name,
            isDirectory = false,
            isHidden = false,
            path = path,
            mineType = "text/plain",
            size = 1L,
            createdDate = 0L,
            updatedDate = 0L,
        )
}
