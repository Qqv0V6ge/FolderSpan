package com.folderspan.service.network

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SftpHostKeyTrustTest {
    private val key = sftpHostKey("sftp.example", 2220, byteArrayOf(1, 2, 3))

    @Test
    fun firstUseRequiresConfirmationAndPersistsForTheSameHostAndPort() = runTest {
        val settings = MapSettings()
        val trust = SftpHostKeyTrust(settings)
        trust.attachDialogHost()
        var attempts = 0
        val connection = async {
            trust.withTrust { attempts++; runCatching { trust.verify(key) } }
        }
        runCurrent()
        val pending = assertNotNull(trust.request.value)
        assertEquals(key, pending.hostKey)
        assertEquals(1, attempts)
        assertTrue(settings.keys.isEmpty())
        trust.respond(pending, true)
        assertTrue(connection.await().getOrThrow())
        assertEquals(2, attempts)
        assertNull(trust.request.value)

        val restored = SftpHostKeyTrust(settings)
        assertTrue(restored.verify(key.copy(host = "SFTP.EXAMPLE")))
        assertFailsWith<SftpUnknownHostKeyException> { restored.verify(key.copy(port = 22)) }
        assertFailsWith<SftpUnknownHostKeyException> { restored.verify(key.copy(host = "other.example")) }
        val changed = assertFailsWith<IllegalStateException> {
            restored.verify(key.copy(fingerprint = "SHA256:changed"))
        }
        assertEquals(AppStrings.ui_sftp_host_key_changed, changed.message)
    }

    @Test
    fun rejectionCancellationAndClosingTheUiDoNotPersistTrust() = runTest {
        val settings = MapSettings()
        val trust = SftpHostKeyTrust(settings)
        trust.attachDialogHost()
        val rejected = async { trust.withTrust { runCatching { trust.verify(key) } } }
        runCurrent()
        trust.respond(assertNotNull(trust.request.value), false)
        assertTrue(rejected.await().isFailure)

        val cancelled = async { trust.withTrust { runCatching { trust.verify(key) } } }
        runCurrent()
        val stale = assertNotNull(trust.request.value)
        cancelled.cancelAndJoin()
        assertNull(trust.request.value)

        val closed = async { trust.withTrust { runCatching { trust.verify(key) } } }
        runCurrent()
        trust.respond(stale, true)
        assertFalse(closed.isCompleted)
        trust.detachDialogHost()
        assertTrue(closed.await().isFailure)
        assertTrue(settings.keys.isEmpty())
        assertNull(trust.request.value)
    }

    @Test
    fun unknownKeyWithoutUiFailsWithoutWaitingOrSaving() = runTest {
        val settings = MapSettings()
        val trust = SftpHostKeyTrust(settings)
        val result = trust.withTrust { runCatching { trust.verify(key) } }
        assertEquals(AppStrings.ui_sftp_host_key_confirmation_required, result.exceptionOrNull()?.message)
        assertNull(trust.request.value)
        assertTrue(settings.keys.isEmpty())
    }

    @Test
    fun concurrentConnectionsCannotReplaceTheKeyJustApproved() = runTest {
        val trust = SftpHostKeyTrust(MapSettings())
        trust.attachDialogHost()
        val first = async { trust.withTrust { runCatching { trust.verify(key) } } }
        val changed = async {
            trust.withTrust { runCatching { trust.verify(key.copy(fingerprint = "SHA256:other")) } }
        }
        runCurrent()
        trust.respond(assertNotNull(trust.request.value), true)
        assertTrue(first.await().isSuccess)
        assertEquals(AppStrings.ui_sftp_host_key_changed, changed.await().exceptionOrNull()?.message)
        assertNull(trust.request.value)
        assertTrue(trust.verify(key))
    }

    @Test
    fun failureToPersistTrustDoesNotRetryTheConnection() = runTest {
        val settings = object : Settings by MapSettings() {
            override fun putString(key: String, value: String) = error("storage unavailable")
        }
        val trust = SftpHostKeyTrust(settings)
        trust.attachDialogHost()
        var attempts = 0
        val connection = async { trust.withTrust { attempts++; runCatching { trust.verify(key) } } }
        runCurrent()
        trust.respond(assertNotNull(trust.request.value), true)
        assertEquals("storage unavailable", connection.await().exceptionOrNull()?.message)
        assertEquals(1, attempts)
        assertNull(trust.request.value)
    }
}
