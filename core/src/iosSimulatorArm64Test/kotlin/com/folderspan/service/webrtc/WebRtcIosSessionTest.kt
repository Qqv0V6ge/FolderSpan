package com.folderspan.service.webrtc

import kotlin.test.Test

class WebRtcIosSessionTest {
    @Test
    fun realDataChannelCarriesSessionRpcAndConsecutiveFiles() = runIosWebRtcTest {
        verifyRealWebRtcSequentialTransfers()
    }
    @Test
    fun concurrentFilesAndRpcShareRealDataChannel() = runIosWebRtcTest {
        verifyRealWebRtcConcurrentTransfersAndRpc()
    }

    @Test
    fun cancellingPausedStreamPreservesSession() = runIosWebRtcTest {
        verifyRealWebRtcPausedStreamCancellation()
    }

    @Test
    fun disconnectFailsPendingRpcAndFreshSessionWorks() = runIosWebRtcTest {
        verifyRealWebRtcDisconnectAndFreshSession()
    }

    @Test
    fun pausedProducerResumesWithoutLosingData() = runIosWebRtcTest {
        verifyRealWebRtcPauseAndResume()
    }

    @Test
    fun closingOnePeerDoesNotCloseAnother() = runIosWebRtcTest {
        verifyRealWebRtcPeerIsolation()
    }

}
