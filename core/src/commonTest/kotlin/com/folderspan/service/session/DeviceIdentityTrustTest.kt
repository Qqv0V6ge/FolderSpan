package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.SocketDevice
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIdentityTrustTest {
    private val advertisedFingerprint = "A".repeat(64)
    private val observedFingerprint = "B".repeat(64)

    @Test
    fun unverifiedDeviceUsesObservedTlsFingerprintAndWaitsForExplicitConsent() = runTest {
        val saved = mutableMapOf<String, String>()
        val trust = trust(saved)
        trust.attachDialogHost()
        var authenticated = false
        val connection = async {
            trust.resolve(device()) { host, port ->
                assertEquals("10.0.0.122", host)
                assertEquals(12040, port)
                device(observedFingerprint)
            }.also { authenticated = true }
        }
        runCurrent()

        val request = assertNotNull(trust.request.value)
        assertEquals(observedFingerprint, request.fingerprint)
        assertEquals("10.0.0.122:12040", request.endpoint)
        assertFalse(authenticated)
        assertTrue(saved.isEmpty())

        trust.respond(request, true)
        val resolved = connection.await()
        assertEquals(observedFingerprint, resolved.tlsFingerprintSha256)
        assertEquals(DeviceDiscoveryStatus.Trusted, resolved.discoveryStatus)
        assertEquals(observedFingerprint, saved[resolved.id])
        assertNull(trust.request.value)
    }

    @Test
    fun cancellationAndDetachedUiDoNotSaveTrustOrContinueConnection() = runTest {
        val saved = mutableMapOf<String, String>()
        val trust = trust(saved)
        trust.attachDialogHost()
        val rejected = async { runCatching { trust.resolve(device()) { _, _ -> device() } } }
        runCurrent()
        trust.respond(assertNotNull(trust.request.value), false)
        assertTrue(rejected.await().isFailure)

        val cancelled = async { trust.resolve(device()) { _, _ -> device() } }
        runCurrent()
        val staleRequest = assertNotNull(trust.request.value)
        cancelled.cancelAndJoin()

        val closed = async { runCatching { trust.resolve(device()) { _, _ -> device() } } }
        runCurrent()
        trust.respond(staleRequest, true)
        assertFalse(closed.isCompleted)
        trust.detachDialogHost()
        assertTrue(closed.await().isFailure)
        assertTrue(saved.isEmpty())
        assertNull(trust.request.value)
    }

    @Test
    fun savedFingerprintIsReusedButAChangedFingerprintRequiresNewConsent() = runTest {
        val saved = mutableMapOf("peer" to advertisedFingerprint)
        val trust = trust(saved)
        assertEquals(
            DeviceDiscoveryStatus.Trusted,
            trust.resolve(device()) { _, _ -> device() }.discoveryStatus,
        )
        trust.attachDialogHost()
        val changed = async { runCatching { trust.resolve(device()) { _, _ -> device(observedFingerprint) } } }
        runCurrent()
        val request = assertNotNull(trust.request.value)
        assertEquals(advertisedFingerprint, request.previousFingerprint)
        assertEquals(observedFingerprint, request.fingerprint)
        trust.respond(request, false)
        assertTrue(changed.await().isFailure)
        assertEquals(advertisedFingerprint, saved["peer"])
    }

    @Test
    fun changedPeerIdentityAndMissingDialogHostCannotGrantTrust() = runTest {
        val saved = mutableMapOf<String, String>()
        val trust = trust(saved)
        assertTrue(runCatching { trust.resolve(device()) { _, _ -> device().withCopy(id = "other") } }.isFailure)
        assertTrue(runCatching { trust.resolve(device()) { _, _ -> device() } }.isFailure)
        assertTrue(saved.isEmpty())
        assertNull(trust.request.value)
    }

    @Test
    fun verifiedDiscoveryKeepsTheExistingConnectionFlow() = runTest {
        val saved = mutableMapOf<String, String>()
        val device = device().withCopy(discoveryStatus = DeviceDiscoveryStatus.Verified)
        val resolved = trust(saved).resolve(device) { _, _ -> error("No bootstrap needed") }
        assertEquals(device, resolved)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun trustedDeviceIsRecheckedAndDiscoveryTrustIsNeverDeserializedFromThePeer() = runTest {
        val known = device().withCopy(discoveryStatus = DeviceDiscoveryStatus.Trusted)
        val decoded = ProtoBufCodec.decode<SocketDevice>(ProtoBufCodec.encode(known))
        assertEquals(DeviceDiscoveryStatus.Unknown, decoded.discoveryStatus)
        val saved = mutableMapOf("peer" to advertisedFingerprint)
        val trust = trust(saved)
        trust.attachDialogHost()
        val changed = async { trust.resolve(known) { _, _ -> device(observedFingerprint) } }
        runCurrent()
        trust.respond(assertNotNull(trust.request.value), true)
        assertEquals(observedFingerprint, changed.await().tlsFingerprintSha256)
    }

    @Test
    fun pendingConfirmationKeepsTheSelectedEndpointAndPreservesOnlyLocalShareGrant() = runTest {
        val saved = mutableMapOf<String, String>()
        val trust = trust(saved)
        trust.attachDialogHost()
        val selected = device().withCopy(shareConnectNonce = "local-grant")
        val connection = async {
            trust.resolve(selected) { _, _ -> device(observedFingerprint).withCopy(host = "10.0.0.99", token = "remote-token") }
        }
        runCurrent()
        selected.host = "10.0.0.88"
        trust.respond(assertNotNull(trust.request.value), true)
        val resolved = connection.await()
        assertEquals("10.0.0.122", resolved.host)
        assertEquals("local-grant", resolved.shareConnectNonce)
        assertEquals("", resolved.token)
    }

    private fun trust(saved: MutableMap<String, String>) = DeviceIdentityTrust(
        expectedFingerprint = { saved[it].orEmpty() },
        saveFingerprint = { id, fingerprint -> saved[id] = fingerprint },
    )

    private fun device(fingerprint: String = advertisedFingerprint) = SocketDevice(
        id = "peer",
        name = "Test device",
        pathSeparator = "/",
        host = "10.0.0.122",
        port = 12042,
        httpsPort = 12040,
        type = DeviceType.JVM,
        tlsFingerprintSha256 = fingerprint,
        discoveryStatus = DeviceDiscoveryStatus.Unverified,
    )
}
