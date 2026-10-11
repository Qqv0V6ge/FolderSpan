package com.folderspan.ui.state.file

import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileListDeviceFallbackTest {
    @Test
    fun activeDeviceConnectionFailureFallsBackToLocal() {
        val device = device("device-1")

        assertTrue(
            shouldFallbackToLocalAfterFileListFailure(
                requestDesk = device,
                activeDesk = device,
                error = IllegalStateException("Connection refused"),
            )
        )
    }

    @Test
    fun localConnectionFailureDoesNotTriggerDeviceFallback() {
        val local = Local()

        assertFalse(
            shouldFallbackToLocalAfterFileListFailure(
                requestDesk = local,
                activeDesk = local,
                error = IllegalStateException("Connection refused"),
            )
        )
    }

    @Test
    fun staleDeviceRequestDoesNotReplaceTheNewActiveDesk() {
        val requestDevice = device("device-1")
        val replacementDevice = device("device-1")

        assertFalse(
            shouldFallbackToLocalAfterFileListFailure(
                requestDesk = requestDevice,
                activeDesk = replacementDevice,
                error = IllegalStateException("Connection refused"),
            )
        )
    }

    @Test
    fun nonConnectionFailureKeepsTheActiveDevice() {
        val device = device("device-1")

        assertFalse(
            shouldFallbackToLocalAfterFileListFailure(
                requestDesk = device,
                activeDesk = device,
                error = IllegalStateException("Remote path does not exist"),
            )
        )
    }

    private fun device(id: String): Device = Device(
        id = id,
        name = id,
        pathSeparator = "/",
        host = mutableMapOf(),
        type = DeviceType.Android,
        token = "token",
    )
}
