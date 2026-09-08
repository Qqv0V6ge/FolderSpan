package com.folderspan.service.webrtc

import com.folderspan.test.runSuspendTest
import kotlin.test.Test

class WebRtcWasmSessionTest {
    @Test
    fun realDataChannelCarriesSessionRpcAndConsecutiveFiles() = runSuspendTest {
        verifyRealWebRtcSequentialTransfers()
    }
    @Test
    fun concurrentFilesAndRpcShareRealDataChannel() = runSuspendTest {
        verifyRealWebRtcConcurrentTransfersAndRpc()
    }

    @Test
    fun cancellingPausedStreamPreservesSession() = runSuspendTest {
        verifyRealWebRtcPausedStreamCancellation()
    }

    @Test
    fun disconnectFailsPendingRpcAndFreshSessionWorks() = runSuspendTest {
        verifyRealWebRtcDisconnectAndFreshSession()
    }

    @Test
    fun pausedProducerResumesWithoutLosingData() = runSuspendTest {
        verifyRealWebRtcPauseAndResume()
    }

    @Test
    fun closingOnePeerDoesNotCloseAnother() = runSuspendTest {
        verifyRealWebRtcPeerIsolation()
    }

}
