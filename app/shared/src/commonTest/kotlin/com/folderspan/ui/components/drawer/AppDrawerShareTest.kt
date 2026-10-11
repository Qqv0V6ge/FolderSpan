package com.folderspan.ui.components.drawer

import com.folderspan.data.main.Local
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDrawerShareTest {

    @Test
    fun currentShareIsSelected() {
        val share = share()

        assertTrue(isSelectedShare(share, share))
    }

    @Test
    fun localDeskAndAnotherShareAreNotSelected() {
        val share = share()

        assertFalse(isSelectedShare(Local(), share))
        assertFalse(isSelectedShare(share(), share))
    }

    private fun share(): Share = Share(
        id = "share-1",
        name = "Share",
        pathSeparator = "/",
        protocol = ShareProtocol.System,
        type = DeviceType.JVM,
    )
}
