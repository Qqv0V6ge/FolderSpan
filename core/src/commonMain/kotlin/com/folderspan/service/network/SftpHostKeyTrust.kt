package com.folderspan.service.network

import com.folderspan.createSettings
import com.russhwolf.settings.Settings
import korlibs.crypto.sha256
import kotlinx.coroutines.CancellationException
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
import kotlin.io.encoding.Base64

data class SftpHostKey(val host: String, val port: Int, val fingerprint: String) {
    val endpoint: String get() = "[$host]:$port"
}

class SftpHostKeyRequest internal constructor(val hostKey: SftpHostKey) {
    internal val answer = CompletableDeferred<Boolean>()
}

internal class SftpUnknownHostKeyException(val hostKey: SftpHostKey) :
    IllegalStateException(AppStrings.ui_sftp_host_key_confirmation_required)

internal fun Throwable.sftpUnknownHostKeyOrNull(): SftpUnknownHostKeyException? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<SftpUnknownHostKeyException>().firstOrNull()

internal fun sftpHostKey(host: String, port: Int, encodedKey: ByteArray): SftpHostKey =
    SftpHostKey(host, port, "SHA256:" + Base64.encode(encodedKey.sha256().bytes).trimEnd('='))

class SftpHostKeyTrust(private val settings: Settings) {
    private val confirmationMutex = Mutex()
    private val dialogHosts = MutableStateFlow(0)
    private val pendingRequest = MutableStateFlow<SftpHostKeyRequest?>(null)
    val request = pendingRequest.asStateFlow()

    fun attachDialogHost() {
        dialogHosts.update { it + 1 }
    }

    fun detachDialogHost() {
        if (dialogHosts.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            pendingRequest.value?.answer?.complete(false)
        }
    }

    fun respond(request: SftpHostKeyRequest, trust: Boolean) {
        if (pendingRequest.value === request) request.answer.complete(trust)
    }

    internal fun verify(hostKey: SftpHostKey): Boolean {
        val expected = settings.getString(settingsKey(hostKey), "")
        if (expected.isEmpty()) throw SftpUnknownHostKeyException(hostKey)
        check(expected == hostKey.fingerprint) { AppStrings.ui_sftp_host_key_changed }
        return true
    }

    internal suspend fun <T> withTrust(block: suspend () -> Result<T>): Result<T> {
        val result = block()
        val unknownKey = result.exceptionOrNull()?.sftpUnknownHostKeyOrNull() ?: return result
        return try {
            if (confirm(unknownKey.hostKey)) block()
            else Result.failure(IllegalStateException(AppStrings.ui_sftp_host_key_not_trusted))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun confirm(hostKey: SftpHostKey): Boolean = confirmationMutex.withLock {
        try {
            return@withLock verify(hostKey)
        } catch (_: SftpUnknownHostKeyException) {
            // Only a previously unknown key can be accepted through first-use confirmation.
        }
        check(dialogHosts.value > 0) { AppStrings.ui_sftp_host_key_confirmation_required }
        val pending = SftpHostKeyRequest(hostKey)
        pendingRequest.value = pending
        try {
            if (dialogHosts.value == 0 || !pending.answer.await()) return@withLock false
            currentCoroutineContext().ensureActive()
            val key = settingsKey(hostKey)
            val expected = settings.getString(key, "")
            check(expected.isEmpty() || expected == hostKey.fingerprint) { AppStrings.ui_sftp_host_key_changed }
            settings.putString(key, hostKey.fingerprint)
            true
        } finally {
            pendingRequest.value = null
            pending.answer.cancel()
        }
    }

    private fun settingsKey(hostKey: SftpHostKey): String =
        "sftpHostKey." + "[${hostKey.host.lowercase()}]:${hostKey.port}".encodeToByteArray().sha256().hexLower

    companion object {
        val shared: SftpHostKeyTrust by lazy { SftpHostKeyTrust(createSettings()) }
    }
}
