package com.folderspan.service.webrtc

import com.folderspan.service.webrtc.models.isDirectIceCandidate
import com.folderspan.service.webrtc.models.withoutNonDirectIceCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectIceCandidatesTest {
    @Test
    fun onlyDirectCandidateTypesSurviveBothMessageAndSdpForms() {
        val host = "candidate:1 1 udp 2122260223 2001:db8::42 44301 typ host"
        val srflx = "candidate:2 1 udp 1686052607 192.0.2.2 34322 typ srflx raddr 10.0.0.2 rport 33322"
        val prflx = "candidate:3 1 udp 1686052607 192.0.2.3 34323 typ prflx"
        val relay = "candidate:4 1 udp 8388607 203.0.113.1 3230 typ relay"
        val unknown = "candidate:5 1 udp 8388607 203.0.113.2 3231 typ unknown"

        listOf(host, srflx, prflx).forEach { assertTrue(it.isDirectIceCandidate()) }
        listOf(relay, unknown, "candidate:broken").forEach { assertFalse(it.isDirectIceCandidate()) }
        val sdp = "v=0\r\na=$host\r\na=$relay\r\na=$unknown\r\na=end-of-candidates\r\n"
        assertEquals("v=0\r\na=$host\r\na=end-of-candidates\r\n", sdp.withoutNonDirectIceCandidates())
    }
}
