package com.folderspan.di

import com.folderspan.proBindings
import com.folderspan.createSettings
import com.folderspan.cleanup.ApplicationDataCleanupState
import com.folderspan.data.file.ShareHistoryStore
import com.folderspan.data.file.TempShareHistoryStore
import com.folderspan.service.DriverFactory
import com.folderspan.service.createDatabase
import com.folderspan.service.bookmark.BookmarkSourceAvailability
import com.folderspan.service.bookmark.ScopedBookmarkRepository
import com.folderspan.service.account.AccountDeviceAutomation
import com.folderspan.service.account.AccountDeviceNonceReplayCache
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.account.InMemoryAccountDeviceTrustRegistry
import com.folderspan.service.http.server.HttpShareFileServer
import com.folderspan.service.http.server.HttpShareFileServerInterface
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadEngine
import com.folderspan.service.http.clipboard.ClipboardUrlDownloader
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspector
import com.folderspan.service.http.clipboard.DefaultClipboardUrlShareInspector
import com.folderspan.service.mcp.McpServerSettingsStore
import com.folderspan.service.mcp.auth.McpTokenRepository
import com.folderspan.service.mcp.automation.McpAutomationFacade
import com.folderspan.service.mcp.automation.McpCatalogFacade
import com.folderspan.service.mcp.automation.McpDeviceFacade
import com.folderspan.service.mcp.automation.McpFileFacade
import com.folderspan.service.mcp.automation.McpFileSharing
import com.folderspan.service.mcp.automation.McpFileSharingFacade
import com.folderspan.service.mcp.automation.McpNetworkFacade
import com.folderspan.service.mcp.automation.McpShareAddressProvider
import com.folderspan.service.mcp.automation.McpSyncFacade
import com.folderspan.service.mcp.automation.McpTaskFacade
import com.folderspan.service.mcp.file.FileContentReader
import com.folderspan.service.mcp.file.FileContentWriter
import com.folderspan.service.mcp.file.FileGatewayTaskSubmitter
import com.folderspan.service.mcp.file.FileGatewayTransferCoordinator
import com.folderspan.service.mcp.file.FolderSpanFileEndpointResolver
import com.folderspan.service.mcp.http.McpAllowedHostProvider
import com.folderspan.service.mcp.http.McpHttpRequestHandler
import com.folderspan.service.mcp.http.McpHttpSecurityPolicy
import com.folderspan.service.mcp.http.McpHttpService
import com.folderspan.service.mcp.http.McpHttpServiceInterface
import com.folderspan.service.mcp.http.mcpAdvertisedHosts
import com.folderspan.service.mcp.protocol.RawMcpStreamableHttpTransport
import com.folderspan.service.mcp.tools.McpToolRegistry
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.device.DeviceSettingsState
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.*
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.dsl.module

val commonModule = module {
    single {
        val settings = createSettings()
        SettingsUtils.init(settings)
        settings
    }
}

val commonScreenModule = module {
    single { MainState() }
    single<TaskFailureResultStore> { TempTaskFailureResultStore() }
    single<TaskRuntimePersistenceStore> { TempTaskRuntimePersistenceStore() }
    single<SyncTaskStore> { TempSyncTaskStore() }
    single<ShareHistoryStore> { TempShareHistoryStore() }
    single { TaskState(get(), get()) }
    single { NotificationState() }
    single { DrawerState(get()) }
    single { CrashState(get()) }
    single { DeviceState() }
    single<AccountDeviceTrustRegistry> { InMemoryAccountDeviceTrustRegistry() }
    single { AccountDeviceNonceReplayCache() }
    single<AccountDeviceAutomation> { get<DeviceState>() }
    single { NetworkState(get()) }
    single { WebRtcRoomState(get()) }
    single { SyncState(get()) }
    single { FileState() }
    single { HomeState() }
    single<ClipboardUrlDownloader> { ClipboardUrlDownloadEngine() }
    single<ClipboardUrlShareInspector> { DefaultClipboardUrlShareInspector() }
    single<ClipboardUrlDownloadResultPresenter> {
        val fileState = get<FileState>()
        ClipboardUrlDownloadResultPresenter { staged ->
            if (ClipboardUrlShareFiles.add(staged)) {
                fileState.mainScope.launch { fileState.openSystemShareFiles() }
                true
            } else {
                false
            }
        }
    }
    single {
        ClipboardUrlDownloadCoordinator(
            engine = get(),
            taskState = get(),
            resultPresenter = get(),
        )
    }
    single { FileFilterState(get()) }
    single { FileOperationState() }
    single<BookmarkSourceAvailability> {
        BookmarkSourceAvailability { scope ->
            when (scope.protocol) {
                com.folderspan.data.file.FileProtocol.Local -> true
                com.folderspan.data.file.FileProtocol.Device ->
                    get<DeviceState>().devices.any { device -> device.id == scope.sourceId }
                com.folderspan.data.file.FileProtocol.Share ->
                    get<DeviceState>().shares.any { share -> share.id == scope.sourceId }
                com.folderspan.data.file.FileProtocol.Network ->
                    get<NetworkState>().connectedNetworks.any { network -> network.protocolId == scope.sourceId }
            }
        }
    }
    single { ScopedBookmarkRepository(get(), get()) }
    single {
        FileBookmarkState(
            repository = get(),
            currentDesk = { get<FileState>().deskType.value },
        )
    }
    single { FileFavoriteState() }
    single { FileRecentState() }
    single { FileShareState() }
    single { SettingsState(get()) }
    proBindings()
    single { ApplicationDataCleanupState() }
    single { DeviceRoleState(get()) }
    single { DevicePermissionState(get()) }
    single { DeviceSettingsState(get()) }
    single { DeviceCertificateState(get()) }

    single { McpServerSettingsStore() }
    single { McpTokenRepository(get()) }
    single { FolderSpanFileEndpointResolver(get(), get()).resolver }
    single { FileContentReader(get()) }
    single { FileContentWriter(get()) }
    single { FileGatewayTransferCoordinator(get()) }
    single {
        FileGatewayTaskSubmitter(
            taskState = get(),
            coordinator = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    single<HttpShareFileServerInterface> { HttpShareFileServer.getInstance(get()) }
    single<McpShareAddressProvider> {
        McpShareAddressProvider { getAllIPAddresses(SocketClientIPEnum.ALL).ifEmpty { listOf("localhost") } }
    }
    single<McpFileSharing> { McpFileSharingFacade(get(), get(), get(), get(), get()) }
    single { McpCatalogFacade(get(), get(), get()) }
    single { McpTaskFacade(get()) }
    single { McpDeviceFacade(get()) }
    single { McpNetworkFacade(get()) }
    single { McpSyncFacade(get()) }
    single { McpFileFacade(get(), get(), get(), get(), get()) }
    single { McpAutomationFacade(get(), get(), get(), get(), get(), get()) }
    single { McpToolRegistry(get()) }
    single { RawMcpStreamableHttpTransport(get(), serverVersion = "1.0") }
    single<McpAllowedHostProvider> {
        McpAllowedHostProvider {
            val lanAccess = get<McpServerSettingsStore>().read().lanAccess
            mcpAdvertisedHosts(
                lanAccess = lanAccess,
                lanAddresses = if (lanAccess) getAllIPAddresses(SocketClientIPEnum.ALL) else emptyList(),
            ).map { item -> item.removePrefix("[").removeSuffix("]") }.toSet()
        }
    }
    single { McpHttpSecurityPolicy(get()) }
    single { McpHttpRequestHandler(get(), get(), get()) }
    single<McpHttpServiceInterface> { McpHttpService(get()) }
}

val commonDatabaseModule = module {
    single {
        val driverFactory = DriverFactory()
        createDatabase(driverFactory)
    }
}
