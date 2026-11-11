package com.folderspan.service.webui

import kotlin.test.Test
import kotlin.test.assertEquals

class WebUiDeviceTimingTest {
    @Test fun openRoundTripProvidesAConservativeOriginAcrossDifferentClocks() {
        assertEquals(10_100L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_100, 10_500))
        assertEquals(19_900L, webUiDeviceFrameReceivedAt(20_000, 10_000, 9_900, 20_200))
    }

    @Test fun fiveSecondCacheBoundaryDoesNotRestartWhenForwarded() {
        assertEquals(10_000L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_000, 14_999))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_000, 15_000))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_000, 100_000))
    }

    @Test fun missingFutureAndReversedClockSamplesAreUnknown() {
        assertEquals(0L, webUiDeviceFrameReceivedAt(0, 100_000, 100_000, 10_000))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 0, 100_000, 10_000))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 100_000, 0, 10_000))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_501, 10_500))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 100_000, 100_000, 9_999))
    }

    @Test fun extremeHostClocksDoNotOverflowIntoAFreshSample() {
        assertEquals(10_050L, webUiDeviceFrameReceivedAt(10_000, Long.MAX_VALUE - 100, Long.MAX_VALUE - 50, 10_100))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, Long.MAX_VALUE, 1, 10_100))
        assertEquals(0L, webUiDeviceFrameReceivedAt(10_000, 1, Long.MAX_VALUE, 10_100))
    }
}
