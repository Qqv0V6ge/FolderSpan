package com.folderspan.pro.di

import com.folderspan.pro.core.network.httpClient
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.repository.AccountDeviceTrustRepository
import com.folderspan.pro.domain.usecase.AccountDeviceTrustCoordinator
import com.folderspan.service.account.AccountDeviceAutomation
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.ui.state.settings.SettingsState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

class AccountDeviceTrustRuntime(
    settingsState: SettingsState,
    registry: AccountDeviceTrustRegistry,
    automation: AccountDeviceAutomation,
    private val client: HttpClient = httpClient(),
) : AccountDeviceTrustRefreshTrigger {
    private val coordinator = AccountDeviceTrustCoordinator(
        settingsState = settingsState,
        registry = registry,
        source = AccountDeviceTrustRepository(UserApiService(client)),
        automation = automation,
    )

    fun start(scope: CoroutineScope) = coordinator.start(scope)

    fun cancel() = coordinator.cancel()

    suspend fun stop() {
        coordinator.stop()
        client.close()
    }

    override suspend fun refreshIfNeeded(force: Boolean): Boolean =
        coordinator.refreshIfNeeded(force)
}
