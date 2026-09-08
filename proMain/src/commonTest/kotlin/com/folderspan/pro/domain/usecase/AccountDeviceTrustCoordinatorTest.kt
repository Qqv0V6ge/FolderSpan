package com.folderspan.pro.domain.usecase

import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.service.account.ACCOUNT_DEVICE_IDENTITY_ALGORITHM
import com.folderspan.service.account.AccountDeviceAutomation
import com.folderspan.service.account.InMemoryAccountDeviceTrustRegistry
import com.folderspan.service.account.AccountDeviceTrustedIdentity
import com.folderspan.ui.state.settings.SettingsState
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeviceTrustCoordinatorTest {
    @Test
    fun enabledLoggedInSessionRefreshesImmediatelyAndExcludesLocalDevice() = runTest {
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val session = MutableStateFlow<AuthSession?>(AuthSession(accessToken = "token-a"))
        val registry = InMemoryAccountDeviceTrustRegistry()
        val source = RecordingSource(
            mutableListOf(AccountDeviceTrustRefreshResult.Success(listOf(identity("local"), identity("peer"))))
        )
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = registry,
            source = source,
            sessionFlow = session,
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { 1_000L },
            delayMillis = { awaitCancellation() },
        )

        coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(listOf("token-a"), source.tokens)
        assertEquals(setOf("peer"), registry.state.value.devices.keys)
        assertTrue(registry.state.value.isFresh(1_000L))
        coordinator.cancel()
    }

    @Test
    fun failedRefreshKeepsUnexpiredSnapshotButUnauthorizedClearsIt() = runTest {
        var now = 1_000L
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val registry = InMemoryAccountDeviceTrustRegistry()
        val source = RecordingSource(
            mutableListOf(
                AccountDeviceTrustRefreshResult.Success(listOf(identity("peer"))),
                AccountDeviceTrustRefreshResult.Failure,
                AccountDeviceTrustRefreshResult.Unauthorized,
            )
        )
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = registry,
            source = source,
            sessionFlow = MutableStateFlow(AuthSession(accessToken = "token")),
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { now },
            delayMillis = { awaitCancellation() },
        )
        coordinator.start(backgroundScope)
        runCurrent()

        now += 1_000L
        assertTrue(coordinator.refreshIfNeeded(force = true))
        assertTrue(registry.state.value.isFresh(now))
        assertFalse(coordinator.refreshIfNeeded(force = true))
        assertTrue(registry.state.value.devices.isEmpty())
        coordinator.cancel()
    }

    @Test
    fun disablingFeatureClearsTrustAndTemporaryAuthorization() = runTest {
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val registry = InMemoryAccountDeviceTrustRegistry()
        val automation = RecordingAutomation()
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = registry,
            source = RecordingSource(
                mutableListOf(AccountDeviceTrustRefreshResult.Success(listOf(identity("peer"))))
            ),
            automation = automation,
            sessionFlow = MutableStateFlow(AuthSession(accessToken = "token")),
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { 1_000L },
            delayMillis = { awaitCancellation() },
        )
        coordinator.start(backgroundScope)
        runCurrent()

        settingsState.setFileShareAccountDeviceAutoConnectEnabled(false)
        runCurrent()

        assertTrue(registry.state.value.devices.isEmpty())
        assertTrue(automation.clearCalls > 0)
        coordinator.cancel()
    }

    @Test
    fun concurrentRefreshRequestsShareOneInFlightCall() = runTest {
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        var calls = 0
        val source = AccountDeviceTrustSource {
            calls++
            if (calls == 1) {
                AccountDeviceTrustRefreshResult.Success(listOf(identity("peer")))
            } else {
                refreshStarted.complete(Unit)
                allowRefresh.await()
                AccountDeviceTrustRefreshResult.Success(listOf(identity("peer")))
            }
        }
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = InMemoryAccountDeviceTrustRegistry(),
            source = source,
            sessionFlow = MutableStateFlow(AuthSession(accessToken = "token")),
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { 1_000L },
            delayMillis = { awaitCancellation() },
        )
        coordinator.start(backgroundScope)
        runCurrent()

        val first = async { coordinator.refreshIfNeeded(force = true) }
        refreshStarted.await()
        val second = async { coordinator.refreshIfNeeded(force = true) }
        runCurrent()

        assertEquals(2, calls)
        allowRefresh.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(2, calls)
        coordinator.cancel()
    }

    @Test
    fun accountSwitchReplacesGenerationAndDiscardsPreviousTrust() = runTest {
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val session = MutableStateFlow<AuthSession?>(AuthSession(accessToken = "token-a"))
        val registry = InMemoryAccountDeviceTrustRegistry()
        val source = AccountDeviceTrustSource { token ->
            AccountDeviceTrustRefreshResult.Success(
                listOf(identity(if (token == "token-a") "peer-a" else "peer-b"))
            )
        }
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = registry,
            source = source,
            sessionFlow = session,
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { 1_000L },
            delayMillis = { awaitCancellation() },
        )
        coordinator.start(backgroundScope)
        runCurrent()
        val firstGeneration = registry.state.value.sessionGeneration

        session.value = AuthSession(accessToken = "token-b")
        runCurrent()

        assertEquals(setOf("peer-b"), registry.state.value.devices.keys)
        assertTrue(registry.state.value.sessionGeneration != firstGeneration)
        coordinator.cancel()
    }

    @Test
    fun failedRefreshAfterExpiryFailsClosed() = runTest {
        var now = 1_000L
        val settingsState = SettingsState(MapSettings()).apply {
            setFileShareAccountDeviceAutoConnectEnabled(true)
        }
        val registry = InMemoryAccountDeviceTrustRegistry()
        val source = RecordingSource(
            mutableListOf(
                AccountDeviceTrustRefreshResult.Success(listOf(identity("peer"))),
                AccountDeviceTrustRefreshResult.Failure,
            )
        )
        val coordinator = AccountDeviceTrustCoordinator(
            settingsState = settingsState,
            registry = registry,
            source = source,
            sessionFlow = MutableStateFlow(AuthSession(accessToken = "token")),
            localDeviceKeyProvider = { "local" },
            nowEpochMillis = { now },
            delayMillis = { awaitCancellation() },
        )
        coordinator.start(backgroundScope)
        runCurrent()

        now += 5 * 60 * 1_000L
        assertFalse(coordinator.refreshIfNeeded(force = true))
        assertFalse(registry.state.value.isFresh(now))
        coordinator.cancel()
    }

    private class RecordingSource(
        private val results: MutableList<AccountDeviceTrustRefreshResult>,
    ) : AccountDeviceTrustSource {
        val tokens = mutableListOf<String>()

        override suspend fun refresh(token: String): AccountDeviceTrustRefreshResult {
            tokens += token
            return results.removeFirstOrNull() ?: AccountDeviceTrustRefreshResult.Failure
        }
    }

    private class RecordingAutomation : AccountDeviceAutomation {
        var clearCalls = 0
        override suspend fun isPermanentlyRejected(deviceKey: String): Boolean = false
        override suspend fun isConnectedOrConnecting(deviceKey: String): Boolean = false
        override suspend fun revokeTemporaryTrust(deviceKey: String, disconnect: Boolean) = Unit
        override suspend fun clearTemporaryTrust(disconnect: Boolean) {
            clearCalls++
        }
    }
}

private fun identity(key: String) = AccountDeviceTrustedIdentity(
    deviceKey = key,
    identityPublicKey = "public-key",
    identityAlgorithm = ACCOUNT_DEVICE_IDENTITY_ALGORITHM,
    identityKeyId = "key-id",
)
