package com.folderspan.service.session

import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.tls.TrustedDeviceCertificateStore
import com.folderspan.service.http.tls.normalizeTlsFingerprintSha256
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

class DeviceIdentityTrustRequest internal constructor(
    val deviceName: String,
    val endpoint: String,
    val fingerprint: String,
    val previousFingerprint: String,
) {
    internal val answer = CompletableDeferred<Boolean>()
}

/** First-use certificate confirmation for devices discovered through an unverified beacon. */
class DeviceIdentityTrust(
    private val expectedFingerprint: (String) -> String,
    private val saveFingerprint: (String, String) -> Unit,
) {
    private val confirmationMutex = Mutex()
    private val dialogHosts = MutableStateFlow(0)
    private val pendingRequest = MutableStateFlow<DeviceIdentityTrustRequest?>(null)
    val request = pendingRequest.asStateFlow()

    fun attachDialogHost() {
        dialogHosts.update { it + 1 }
    }

    fun detachDialogHost() {
        if (dialogHosts.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            pendingRequest.value?.answer?.complete(false)
        }
    }

    fun respond(request: DeviceIdentityTrustRequest, trust: Boolean) {
        if (pendingRequest.value === request) request.answer.complete(trust)
    }

    suspend fun resolve(device: SocketDevice): SocketDevice =
        resolve(device, ::identifyDeviceSessionEndpoint)

    internal suspend fun resolve(
        device: SocketDevice,
        identify: suspend (String, Int) -> SocketDevice,
    ): SocketDevice {
        if (device.discoveryStatus != DeviceDiscoveryStatus.Unverified &&
            device.discoveryStatus != DeviceDiscoveryStatus.Trusted
        ) return device
        val selected = device.withCopy()
        // Identify sends no credentials or authorization. Its bootstrap connection is closed before prompting.
        val observed = identify(selected.host, selected.httpsPort)
        check(observed.id == selected.id) { AppStrings.ui_device_identity_mismatch }
        val fingerprint = normalizeTlsFingerprintSha256(observed.tlsFingerprintSha256)
        check(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'A'..'F' }) {
            AppStrings.ui_device_identity_invalid_fingerprint
        }
        confirmationMutex.withLock {
            val previous = normalizeTlsFingerprintSha256(expectedFingerprint(selected.id))
            if (previous != fingerprint) {
                check(dialogHosts.value > 0) { AppStrings.ui_device_identity_confirmation_required }
                val pending = DeviceIdentityTrustRequest(
                    deviceName = selected.name,
                    endpoint = if (':' in selected.host) "[${selected.host}]:${selected.httpsPort}"
                        else "${selected.host}:${selected.httpsPort}",
                    fingerprint = fingerprint,
                    previousFingerprint = previous,
                )
                pendingRequest.value = pending
                try {
                    check(dialogHosts.value > 0 && pending.answer.await()) { AppStrings.ui_device_identity_not_trusted }
                    currentCoroutineContext().ensureActive()
                    check(dialogHosts.value > 0) { AppStrings.ui_device_identity_confirmation_required }
                    check(normalizeTlsFingerprintSha256(expectedFingerprint(selected.id)) == previous) {
                        AppStrings.ui_device_identity_trust_changed
                    }
                    saveFingerprint(selected.id, fingerprint)
                } finally {
                    pendingRequest.value = null
                    pending.answer.cancel()
                }
            }
        }
        return observed.withCopy(
            host = selected.host,
            httpsPort = selected.httpsPort,
            connectType = selected.connectType,
            tlsFingerprintSha256 = fingerprint,
            discoveryStatus = DeviceDiscoveryStatus.Trusted,
            token = "",
            shareConnectNonce = selected.shareConnectNonce,
            httpClient = null,
            sessionClient = null,
        )
    }

    companion object {
        val shared: DeviceIdentityTrust by lazy {
            DeviceIdentityTrust(
                expectedFingerprint = TrustedDeviceCertificateStore::expectedFingerprint,
                saveFingerprint = { id, fingerprint -> TrustedDeviceCertificateStore.save(id, fingerprint) },
            )
        }
    }
}
