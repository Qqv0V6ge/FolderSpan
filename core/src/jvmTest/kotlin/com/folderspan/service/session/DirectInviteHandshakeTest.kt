package com.folderspan.service.session

import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.routes.RawHttpApiDispatcher
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import com.folderspan.service.data.DeviceSessionBootstrapAuthorizationType
import com.folderspan.service.data.EmptyRequest
import com.folderspan.service.http.tls.DeviceTlsIdentity
import com.folderspan.utils.ProtoBufCodec
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectInviteHandshakeTest {
    @Test
    fun verifiedOfferBindsCertificateDevicesChallengeCredentialAttemptAndDtls() = runTest {
        val certificate = requireNotNull(DeviceTlsIdentity.certificatePem())
        val offer = createDirectInviteOffer(invite, challenge, "device-b", "attempt-1", sdp, certificate)
        val binding = assertNotNull(verify(offer))
        val transported = ProtoBufCodec.decode<DirectInviteOffer>(ProtoBufCodec.encode(offer))

        assertEquals(binding, verify(transported))
        assertEquals("device-b", binding.initiatorDeviceId)
        val trustedFingerprint = DeviceTlsIdentity.loadOrCreate().fingerprintSha256
        assertEquals(binding, verify(offer, trustedFingerprint = trustedFingerprint))
        assertNull(verify(offer, trustedFingerprint = "F".repeat(64)))
        assertNull(verify(offer, verifierChallenge = challenge.copy(nonce = "C".repeat(43))))
        assertNull(verify(offer, targetDeviceId = "device-c"))
        assertNull(verify(offer, targetFingerprint = "B".repeat(64)))
        assertNull(verify(offer.copy(deviceId = "device-c")))
        assertNull(verify(offer.copy(connectionAttemptId = "attempt-2")))
        assertNull(verify(offer.copy(credential = "A".repeat(42) + "E")))
        assertNull(verify(offer.copy(sdp = sdp.replace("AB:", "AC:"))))
        assertNull(verify(offer.copy(sdp = sdp + "a=fingerprint:sha-256 ${"CD:".repeat(31)}CD\r\n")))
    }

    @Test
    fun incompatibleVersionOrOversizedOfferStopsBeforeSigning() = runTest {
        var signCalls = 0
        val sign: suspend (ByteArray) -> String? = { signCalls++; "invalid" }
        val certificate = requireNotNull(DeviceTlsIdentity.certificatePem())
        assertFailsWith<IllegalArgumentException> {
            createDirectInviteOffer(invite, challenge.copy(version = 0), "device-b", "attempt-1", sdp, certificate, sign)
        }
        assertFailsWith<IllegalArgumentException> {
            createDirectInviteOffer(invite, challenge, "device-b", "attempt-1", sdp.repeat(1000), certificate, sign)
        }
        assertEquals(0, signCalls)
    }

    @Test
    fun pinnedTlsExchangeDoesNotAuthorizeFileRpcOrOrdinaryConnect() = runBlocking {
        val fingerprint = DeviceTlsIdentity.loadOrCreate().fingerprintSha256
        val loopback = InetAddress.getByName("127.0.0.1")
        val listener = DeviceTlsIdentity.createSessionServerSslContext().serverSocketFactory
            .createServerSocket(0, 2, loopback) as SSLServerSocket
        val tlsInvite = invite.copy(host = loopback.hostAddress, port = listener.localPort, fingerprintSha256 = fingerprint)
        val authorization = DeviceSessionBootstrapAuthorization(
            DeviceSessionBootstrapAuthorizationType.WEB_RTC_PREAPPROVED, "test-only-authorization", "attempt-1",
        )
        val accepted = launch(Dispatchers.IO) {
            repeat(2) {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.advertiseDeviceSessionAlpn()
                    socket.soTimeout = 8_000
                    if (runCatching { socket.startHandshake() }.isFailure) return@use
                    check(socket.hasDeviceSessionAlpn())
                    DeviceSessionServerRuntimeLauncher.run(
                        dispatcher = RawHttpApiDispatcher(),
                        channel = JvmDeviceSessionByteChannel(socket),
                        remoteHost = loopback.hostAddress,
                        scope = this,
                        approvalContext = DeviceSessionServerApprovalContext.Standard,
                        directInviteControl = {
                            DirectInviteControlHandler({ invite.deviceId }, { fingerprint }) { _, binding ->
                                assertEquals("device-b", binding.initiatorDeviceId)
                                DirectInviteAnswer(sdp, authorization)
                            }
                        },
                    )
                }
            }
        }
        try {
            val wrongFingerprint = if (fingerprint[0] == 'A') "B".repeat(64) else "A".repeat(64)
            assertFailsWith<SSLHandshakeException> {
                withDirectInviteControl(tlsInvite.copy(fingerprintSha256 = wrongFingerprint), this) {
                    error("certificate mismatch reached the control exchange")
                }
            }
            withDirectInviteControl(tlsInvite, this) { freshChallenge ->
                assertTrue(freshChallenge.nonce.isNotBlank())
                val certificate = requireNotNull(DeviceTlsIdentity.certificatePem())
                val offer = createDirectInviteOffer(tlsInvite, freshChallenge, "device-b", "attempt-1", sdp, certificate)
                val answer = rpcValue<DirectInviteOffer, DirectInviteAnswer>(DIRECT_INVITE_OFFER_RPC, offer)
                assertEquals(authorization, answer.authorization)
                for (method in listOf(DEVICE_SESSION_RPC_ROOT_PATHS, DEVICE_SESSION_RPC_CONNECT)) {
                    assertEquals(DEVICE_SESSION_RPC_UNAUTHORIZED, rpc(method, ProtoBufCodec.encode(EmptyRequest())).status)
                }
            }
        } finally {
            listener.close()
            accepted.cancelAndJoin()
        }
    }

    private suspend fun verify(
        offer: DirectInviteOffer,
        verifierChallenge: DirectInviteChallenge = challenge,
        targetDeviceId: String = invite.deviceId,
        targetFingerprint: String = invite.fingerprintSha256,
        trustedFingerprint: String = "",
    ): DirectInviteBinding? = verifyDirectInviteOffer(
        offer, verifierChallenge, targetDeviceId, targetFingerprint, trustedFingerprint, trustedKey = "",
    )

    companion object {
        private val invite = WebRtcDirectInvite("device-a", "localhost", 48484, "A".repeat(64), "A".repeat(43), 1893456000000)
        private val challenge = DirectInviteChallenge(DIRECT_INVITE_HANDSHAKE_VERSION, "A".repeat(42) + "I")
        private val sdp = "v=0\r\na=fingerprint:sha-256 ${"AB:".repeat(31)}AB\r\n"
    }
}
