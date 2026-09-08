package com.folderspan.pro.di

import com.folderspan.pro.core.network.KEY_PRO_API_HEADER_OVERRIDE
import com.folderspan.pro.core.network.attachmentDownloadHttpClient
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import com.folderspan.pro.core.network.decodeProApiHeaderOverride
import com.folderspan.pro.core.network.httpClient
import com.folderspan.pro.data.remote.api.FeedbackApiService
import com.folderspan.pro.data.remote.api.PluginApiService
import com.folderspan.pro.data.remote.api.SettingApiService
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.repository.DefaultAuthRepository
import com.folderspan.pro.data.repository.DefaultFeedbackRepository
import com.folderspan.pro.data.repository.DefaultPluginRepository
import com.folderspan.pro.data.repository.DefaultSettingRepository
import com.folderspan.pro.data.repository.DefaultUserRepository
import com.folderspan.pro.domain.repository.FeedbackRepository
import com.folderspan.pro.domain.repository.PluginRepository
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.DefaultConfigurationSnapshots
import com.folderspan.pro.domain.usecase.DefaultSyncSnapshots
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.state.settings.SettingsState
import com.russhwolf.settings.Settings
import io.ktor.client.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class AppServices(
    val settings: Settings,
    val client: HttpClient = httpClient(),
    val attachmentDownloadClient: HttpClient = attachmentDownloadHttpClient(),
) : KoinComponent {
    private val apiResponseCacheStore = FileApiResponseCacheStore()
    val userApiService: UserApiService = UserApiService(client, cacheStore = apiResponseCacheStore)
    val pluginApiService: PluginApiService = PluginApiService(client, cacheStore = apiResponseCacheStore)
    val settingApiService: SettingApiService = SettingApiService(
        client,
        cacheStore = apiResponseCacheStore,
        headerOverrideProvider = { settings.proApiHeaderOverride() },
    )
    val feedbackApiService: FeedbackApiService = FeedbackApiService(
        client = client,
        attachmentDownloadClient = attachmentDownloadClient,
    )
    val authRepository: DefaultAuthRepository = DefaultAuthRepository(userApiService)
    val userRepository: UserRepository = DefaultUserRepository(userApiService)
    val pluginRepository: PluginRepository = DefaultPluginRepository(pluginApiService)
    val settingRepository: SettingRepository = DefaultSettingRepository(settingApiService)
    val feedbackRepository: FeedbackRepository = DefaultFeedbackRepository(feedbackApiService)
    val feedbackSessionService: FeedbackSessionService = FeedbackSessionService(feedbackRepository, userRepository)
    val syncSnapshots: DefaultSyncSnapshots = DefaultSyncSnapshots(settings)
    val configurationSnapshots: DefaultConfigurationSnapshots = DefaultConfigurationSnapshots()
    val deviceSettingsSyncService: DeviceSettingsSyncService = DeviceSettingsSyncService(
        repository = settingRepository,
        requestHeaderDeviceKeyProvider = { settings.proApiHeaderOverride()?.deviceKey },
        syncSnapshots = syncSnapshots,
        configurationSnapshots = configurationSnapshots.providers,
        onSettingsApplied = { reloadAppSettingStates() },
    )

    fun close() {
        client.close()
        if (attachmentDownloadClient !== client) attachmentDownloadClient.close()
    }
}

private fun Settings.proApiHeaderOverride() =
    decodeProApiHeaderOverride(getString(KEY_PRO_API_HEADER_OVERRIDE, ""))

private fun KoinComponent.optionalSettingsState(): SettingsState? =
    runCatching { get<SettingsState>() }.getOrNull()

private fun KoinComponent.optionalDrawerState(): DrawerState? =
    runCatching { get<DrawerState>() }.getOrNull()

private fun KoinComponent.reloadAppSettingStates() {
    optionalSettingsState()?.reloadFromSettings()
    optionalDrawerState()?.reloadFromSettings()
}
