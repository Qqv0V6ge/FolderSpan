package com.folderspan.pro.domain.usecase

import com.folderspan.getSocketDevice
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.service.account.ACCOUNT_DEVICE_TRUST_TTL_MILLIS
import com.folderspan.service.account.AccountDeviceAutomation
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.account.AccountDeviceTrustUpdateResult
import com.folderspan.service.account.AccountDeviceTrustedIdentity
import com.folderspan.service.account.NoOpAccountDeviceAutomation
import com.folderspan.ui.state.settings.SettingsState
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AccountDeviceTrustRefreshResult {
    data class Success(val devices: List<AccountDeviceTrustedIdentity>) : AccountDeviceTrustRefreshResult
    data object Unauthorized : AccountDeviceTrustRefreshResult
    data object Failure : AccountDeviceTrustRefreshResult
}

fun interface AccountDeviceTrustSource {
    suspend fun refresh(token: String): AccountDeviceTrustRefreshResult
}

class AccountDeviceTrustCoordinator(
    private val settingsState: SettingsState,
    private val registry: AccountDeviceTrustRegistry,
    private val source: AccountDeviceTrustSource,
    private val automation: AccountDeviceAutomation = NoOpAccountDeviceAutomation,
    private val sessionFlow: StateFlow<AuthSession?> = SessionManager.session,
    private val localDeviceKeyProvider: () -> String = { getSocketDevice().id },
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val delayMillis: suspend (Long) -> Unit = { value -> delay(value) },
) : AccountDeviceTrustRefreshTrigger {
    private val refreshMutex = Mutex()
    private var refreshInFlight: CompletableDeferred<Boolean>? = null
    private var lifecycleJob: Job? = null
    private var sessionCounter = 0L
    private var activeContext: ActiveContext? = null

    fun start(scope: CoroutineScope) {
        if (lifecycleJob?.isActive == true) return
        lifecycleJob = scope.launch {
            combine(
                settingsState.fileShareAccountDeviceAutoConnectEnabled,
                sessionFlow,
            ) { enabled, session -> Eligibility(enabled, session) }
                .distinctUntilChanged()
                .collectLatest { eligibility ->
                    val session = eligibility.session
                    val token = session.activeTokenOrNull(nowEpochMillis() / 1_000L)
                    if (!eligibility.enabled || token == null) {
                        activeContext = null
                        registry.clear()
                        automation.clearTemporaryTrust(disconnect = true)
                        return@collectLatest
                    }
                    runEligibleSession(token)
                }
        }
    }

    override suspend fun refreshIfNeeded(force: Boolean): Boolean {
        val (result, ownsRefresh) = refreshMutex.withLock {
            refreshInFlight?.let { inFlight -> inFlight to false }
                ?: (CompletableDeferred<Boolean>().also { refreshInFlight = it } to true)
        }
        if (!ownsRefresh) return result.await()

        try {
            val refreshed = performRefresh(force)
            result.complete(refreshed)
            return refreshed
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            refreshMutex.withLock {
                if (refreshInFlight === result) refreshInFlight = null
            }
        }
    }

    private suspend fun performRefresh(force: Boolean): Boolean {
        val context = activeContext ?: return false
        val current = registry.state.value
        if (!force && !current.isNearExpiry(nowEpochMillis())) return true

        val result = try {
            source.refresh(context.token)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            AccountDeviceTrustRefreshResult.Failure
        }
        return activeContext == context && when (result) {
            is AccountDeviceTrustRefreshResult.Success -> {
                val fetchedAt = nowEpochMillis()
                val update = registry.replaceSnapshot(
                    sessionGeneration = context.generation,
                    fetchedAtEpochMillis = fetchedAt,
                    expiresAtEpochMillis = fetchedAt + ACCOUNT_DEVICE_TRUST_TTL_MILLIS,
                    devices = result.devices,
                )
                if (update.result == AccountDeviceTrustUpdateResult.Applied) {
                    update.removedDeviceKeys.forEach { deviceKey ->
                        automation.revokeTemporaryTrust(deviceKey, disconnect = true)
                    }
                    true
                } else {
                    false
                }
            }

            AccountDeviceTrustRefreshResult.Unauthorized -> {
                activeContext = null
                registry.clear(context.generation)
                automation.clearTemporaryTrust(disconnect = true)
                false
            }

            AccountDeviceTrustRefreshResult.Failure -> registry.state.value.isFresh(nowEpochMillis())
        }
    }

    suspend fun stop() {
        lifecycleJob?.cancel()
        lifecycleJob?.join()
        lifecycleJob = null
        clearRuntimeState()
    }

    fun cancel() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        activeContext = null
        registry.clear()
    }

    private suspend fun runEligibleSession(token: String) = coroutineScope {
        val localDeviceKey = localDeviceKeyProvider().trim()
        if (localDeviceKey.isBlank()) return@coroutineScope
        val context = ActiveContext(
            generation = "account-device-trust-${++sessionCounter}",
            token = token,
        )
        registry.beginSession(context.generation, localDeviceKey)
        activeContext = context
        try {
            refreshIfNeeded(force = true)
            while (isActive && activeContext == context) {
                delayMillis(TRUST_REFRESH_POLL_MILLIS)
                refreshIfNeeded(force = false)
            }
        } finally {
            if (activeContext == context) activeContext = null
            registry.clear(context.generation)
            withContext(NonCancellable) {
                automation.clearTemporaryTrust(disconnect = true)
            }
        }
    }

    private suspend fun clearRuntimeState() {
        activeContext = null
        registry.clear()
        automation.clearTemporaryTrust(disconnect = true)
    }

    private data class ActiveContext(val generation: String, val token: String)
    private data class Eligibility(val enabled: Boolean, val session: AuthSession?)
}

private fun AuthSession?.activeTokenOrNull(nowEpochSeconds: Long): String? {
    val session = this ?: return null
    if (session.accessToken.isBlank()) return null
    if (session.expiresAtEpochSeconds?.let { value -> value <= nowEpochSeconds } == true) return null
    return session.accessToken
}

private const val TRUST_REFRESH_POLL_MILLIS = 30_000L
