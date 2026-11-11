package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeShareCopyPolicyTest {
    @Test
    fun everySourceRejectsNativeShareDestinationsBeforeRoutingToDeviceClients() {
        FileProtocol.entries.forEach { source ->
            assertEquals(CopyRoute.Unsupported, resolveCopyRoute(source, FileProtocol.Share, "source"))
        }
        assertFailsWith<IllegalArgumentException> { requireWritableCopyDestination(FileProtocol.Share) }
        listOf(FileProtocol.Local, FileProtocol.Device, FileProtocol.Network).forEach(::requireWritableCopyDestination)
    }

    @Test
    fun aForgedWritableReasonCannotEnableANativeShareDestination() {
        val local = CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Local), "local", "/")
        val share = CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Share, "share"), "session", "/")
        val draft = CopyDestinationState("runtime", listOf(CopyFileSnapshot(local,
            FileSimpleInfo.pathFileSimpleInfo("/source.txt").copy(isDirectory = false))))
        val target = CopyDestination(CopyFileSnapshot(share,
            FileSimpleInfo.pathFileSimpleInfo("/shared").copy(protocol = FileProtocol.Share, protocolId = "share")),
            "Share", "Share", unavailableReason = null)
        assertEquals(CopyDestinationSelectionResult.Unavailable, draft.select(target))
        assertTrue(draft.selected.value.isEmpty())
    }

    @Test
    fun readOnlyShareSourcesStillRouteToAllSupportedTargets() {
        assertEquals(CopyRoute.ShareToLocal, resolveCopyRoute(FileProtocol.Share, FileProtocol.Local, "share"))
        assertEquals(CopyRoute.ShareToDevice, resolveCopyRoute(FileProtocol.Share, FileProtocol.Device, "share"))
        assertEquals(CopyRoute.ShareToNetwork, resolveCopyRoute(FileProtocol.Share, FileProtocol.Network, "share"))
    }
}
