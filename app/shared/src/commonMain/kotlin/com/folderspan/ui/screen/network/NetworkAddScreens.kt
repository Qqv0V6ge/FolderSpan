package com.folderspan.ui.screen.network

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.*
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.buttons.TestConnectionButton
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.network.form.*
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.parseNetworkAddress
import com.folderspan.utils.parseShareNetworkLink
import com.folderspan.utils.parseSmbAddress
import com.folderspan.utils.parseWebDavBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

class NetworkAddEntryScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val networkState = koinInject<NetworkState>()
        val notificationScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope {
            CoroutineExceptionHandler { _, error ->
                notificationScope.launch { snackbarHostState.showLatestSnackbar(error.message ?: AppStrings.ui_connection_failed) }
            }
        }

        val initialProtocol = remember {
            if (PlatformType == DeviceType.JS) NetworkAddProtocol.Share else NetworkAddProtocol.FTP
        }
        var protocol by rememberSaveable { mutableStateOf(initialProtocol) }
        val protocolOptions = remember { NetworkAddProtocol.entries.map { item -> item.label } + addedStorageProtocols.map(::storageProtocolLabel) }
        var storageProtocolName by rememberSaveable { mutableStateOf<String?>(null) }
        val storageForm = storageProtocolName?.let { selected ->
            rememberStorageFormState(selected, snackbar = snackbarHostState) { network ->
                networkState.addNetwork(network, persist = true)
                navigator.pop()
            }
        }

        var networkName by rememberSaveable { mutableStateOf("") }
        var networkHost by rememberSaveable { mutableStateOf("") }
        var networkUsername by rememberSaveable { mutableStateOf("") }
        var networkPassword by rememberSaveable { mutableStateOf("") }

        var ftpPassiveMode by rememberSaveable { mutableStateOf(true) }
        var ftpFtpsEnabled by rememberSaveable { mutableStateOf(false) }
        var ftpPathEncoding by rememberSaveable { mutableStateOf(FtpPathEncoding.Auto) }

        var sftpPrivateKey by rememberSaveable { mutableStateOf("") }
        var sftpKnownHosts by rememberSaveable { mutableStateOf("") }
        var sftpAuthType by rememberSaveable { mutableStateOf(SftpAuthType.Password) }

        var smbShareName by rememberSaveable { mutableStateOf("") }
        var smbDomain by rememberSaveable { mutableStateOf("") }

        var webDavAuthType by rememberSaveable { mutableStateOf(WebDavAuthType.Basic) }
        var webDavToken by rememberSaveable { mutableStateOf("") }
        var webDavTokenHeaderName by rememberSaveable { mutableStateOf("Authorization") }
        var webDavTokenPrefix by rememberSaveable { mutableStateOf("Bearer") }
        var webDavHeaders by rememberSaveable(stateSaver = WebDavHeaderEntryListSaver) {
            mutableStateOf(emptyList())
        }

        var s3Bucket by rememberSaveable { mutableStateOf("") }
        var s3Region by rememberSaveable { mutableStateOf("") }
        var s3SessionToken by rememberSaveable { mutableStateOf("") }
        var s3ForcePathStyle by rememberSaveable { mutableStateOf(false) }

        var shareName by rememberSaveable { mutableStateOf("") }
        var shareAddress by rememberSaveable { mutableStateOf("") }
        var sharePassword by rememberSaveable { mutableStateOf("") }

        var isTesting by remember { mutableStateOf(false) }

        val hostEmpty = networkHost.isBlank()
        val parsedShare = remember(shareAddress, protocol) {
            parseShareNetworkLink(shareAddress, protocol.port)
        }
        val ftpParsedHost = remember(networkHost, protocol) {
            if (protocol == NetworkAddProtocol.FTP) parseNetworkAddress(networkHost, 21) else null
        }
        val sftpParsedHost = remember(networkHost, protocol) {
            if (protocol == NetworkAddProtocol.SFTP) parseNetworkAddress(networkHost, 22) else null
        }
        val smbParsedHost = remember(networkHost, protocol) {
            if (protocol == NetworkAddProtocol.SMB) parseSmbAddress(networkHost, 445) else null
        }
        val webDavParsedHost = remember(networkHost, protocol) {
            if (protocol == NetworkAddProtocol.WebDav) parseWebDavBaseUrl(networkHost) else null
        }
        val s3ParsedEndpoint = remember(networkHost, protocol) {
            if (protocol == NetworkAddProtocol.S3 && networkHost.isNotBlank()) {
                parseWebDavBaseUrl(networkHost)
            } else {
                null
            }
        }
        val shareError = shareAddress.isBlank() || parsedShare == null
        val resolvedBaseUrl = parsedShare?.baseUrl.orEmpty()
        val shareErrorMessage = when {
            shareAddress.isBlank() -> AppStrings.ui_link_address_cannot_empty
            parsedShare == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val ftpHostError = hostEmpty || ftpParsedHost == null
        val sftpHostError = hostEmpty || sftpParsedHost == null
        val smbHostError = hostEmpty || smbParsedHost == null
        val resolvedSmbShareName = smbShareName.trim().ifBlank { smbParsedHost?.shareName.orEmpty() }
        val smbShareError = resolvedSmbShareName.isBlank()
        val webDavHostError = hostEmpty || webDavParsedHost == null
        val s3HostError = hostEmpty || s3ParsedEndpoint == null
        val s3AccessKeyError = networkUsername.isBlank()
        val s3SecretKeyError = networkPassword.isBlank()
        val s3BucketError = s3Bucket.isBlank()
        val s3RegionError = s3Region.isBlank()
        val ftpHostErrorMessage = when {
            hostEmpty -> AppStrings.ui_link_address_cannot_empty
            ftpParsedHost == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val sftpHostErrorMessage = when {
            hostEmpty -> AppStrings.ui_link_address_cannot_empty
            sftpParsedHost == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val smbHostErrorMessage = when {
            hostEmpty -> AppStrings.ui_link_address_cannot_empty
            smbParsedHost == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val smbShareErrorMessage = AppStrings.ui_please_enter_share_name_include_share_name_link_address
        val webDavHostErrorMessage = when {
            hostEmpty -> AppStrings.ui_link_address_cannot_empty
            webDavParsedHost == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val s3HostErrorMessage = when {
            hostEmpty -> AppStrings.ui_endpoint_cannot_empty
            s3ParsedEndpoint == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val canSave = when (protocol) {
            NetworkAddProtocol.Share -> !shareError && shareAddress.isNotBlank()
            NetworkAddProtocol.FTP -> !ftpHostError
            NetworkAddProtocol.SFTP -> !sftpHostError
            NetworkAddProtocol.SMB -> !(smbHostError || smbShareError)
            NetworkAddProtocol.WebDav -> !webDavHostError
            NetworkAddProtocol.S3 -> !(s3HostError || s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError)
        }
        val canTest = when (protocol) {
            NetworkAddProtocol.Share -> !shareError
            NetworkAddProtocol.FTP -> !ftpHostError
            NetworkAddProtocol.SFTP -> !sftpHostError
            NetworkAddProtocol.SMB -> !(smbHostError || smbShareError)
            NetworkAddProtocol.WebDav -> !webDavHostError
            NetworkAddProtocol.S3 -> !(s3HostError || s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError)
        }

        fun currentNetwork(): Network? {
            if (!canSave) return null
            return when (protocol) {
                NetworkAddProtocol.Share -> {
                    val link = parsedShare ?: return null
                    NetworkShare(
                        name = shareName.trim().ifBlank { link.hostLabel },
                        baseUrl = link.baseUrl,
                        password = sharePassword.trim().ifBlank { link.password.orEmpty() }
                    )
                }
                NetworkAddProtocol.FTP -> {
                    val parsedHost = ftpParsedHost ?: return null
                    Network(
                        name = networkName.trim().ifBlank { defaultNetworkName(protocol.name, networkHost) },
                        pathSeparator = "/",
                        protocol = NetworkProtocol.FTP.name,
                        host = parsedHost.normalizedHost,
                        username = networkUsername.trim(),
                        password = networkPassword,
                        extras = NetworkDriveExtras(
                            ftp = FtpDriveExtras(
                                passiveMode = ftpPassiveMode,
                                ftpsEnabled = ftpFtpsEnabled,
                                pathEncoding = ftpPathEncoding
                            )
                        )
                    )
                }
                NetworkAddProtocol.SFTP -> {
                    val parsedHost = sftpParsedHost ?: return null
                    Network(
                        name = networkName.trim().ifBlank { defaultNetworkName(protocol.name, networkHost) },
                        pathSeparator = "/",
                        protocol = NetworkProtocol.SFTP.name,
                        host = parsedHost.normalizedHost,
                        username = networkUsername.trim(),
                        password = sftpAuthType.selectedPassword(networkPassword),
                        extras = NetworkDriveExtras(
                            sftp = SftpDriveExtras(
                                privateKey = sftpAuthType.selectedPrivateKey(sftpPrivateKey),
                                knownHosts = sftpKnownHosts
                            )
                        )
                    )
                }
                NetworkAddProtocol.SMB -> {
                    val parsedHost = smbParsedHost ?: return null
                    Network(
                        name = networkName.trim().ifBlank { defaultNetworkName(protocol.name, networkHost) },
                        pathSeparator = "\\",
                        protocol = NetworkProtocol.SMB.name,
                        host = parsedHost.normalizedHost,
                        username = networkUsername.trim(),
                        password = networkPassword,
                        extras = NetworkDriveExtras(
                            smb = SmbDriveExtras(
                                share = resolvedSmbShareName,
                                domain = smbDomain.trim()
                            )
                        )
                    )
                }
                NetworkAddProtocol.WebDav -> {
                    val resolvedHost = webDavParsedHost?.normalizedBaseUrl ?: return null
                    Network(
                        name = networkName.trim().ifBlank { defaultNetworkName(protocol.name, networkHost) },
                        pathSeparator = "/",
                        protocol = NetworkProtocol.WebDav.name,
                        host = resolvedHost,
                        username = networkUsername.trim(),
                        password = networkPassword,
                        extras = NetworkDriveExtras(
                            webdav = WebDavDriveExtras(
                                authType = webDavAuthType,
                                token = webDavToken,
                                tokenHeaderName = webDavTokenHeaderName.trim().ifBlank { "Authorization" },
                                tokenPrefix = webDavTokenPrefix,
                                headers = webDavHeaders.toHeaderMap()
                            )
                        )
                    )
                }
                NetworkAddProtocol.S3 -> {
                    val parsedEndpoint = s3ParsedEndpoint ?: return null
                    val resolvedEndpoint = parsedEndpoint.normalizedBaseUrl
                    Network(
                        name = networkName.trim().ifBlank { defaultNetworkName(protocol.name, networkHost) },
                        pathSeparator = "/",
                        protocol = NetworkProtocol.S3.name,
                        host = resolvedEndpoint,
                        username = networkUsername.trim(),
                        password = networkPassword,
                        extras = NetworkDriveExtras(
                            s3 = S3DriveExtras(
                                bucket = s3Bucket.trim(),
                                region = s3Region.trim(),
                                endpoint = resolvedEndpoint,
                                sessionToken = s3SessionToken.trim(),
                                forcePathStyle = s3ForcePathStyle
                            )
                        )
                    )
                }
            }
        }
        val content = rememberStorageContentFormState(storageProtocolName ?: protocol.name,
            if (storageForm == null) currentNetwork() else null)
        val activeContent = storageForm?.content ?: content
        var isSaving by remember { mutableStateOf(false) }

        val handleSaveClick: () -> Unit = save@{
            if (storageForm != null) {
                storageForm.save()
                return@save
            }
            if (isSaving) return@save
            val selected = currentNetwork() ?: return@save
            isSaving = true
            scope.launch {
                try {
                    if (content.supportedProtocol) content.save(selected) {
                        networkState.addNetwork(it, persist = true)
                    } else networkState.addNetwork(selected, persist = true)
                    navigator.pop()
                } finally { isSaving = false }
            }
        }

        val handleTestClick: () -> Unit = test@{
            if (storageForm != null) {
                storageForm.testConnection()
                return@test
            }
            if (!canTest || isTesting) return@test
            val testNetwork = currentNetwork() ?: return@test
            scope.launch {
                isTesting = true
                try {
                    val rootPath = testNetwork.getRootPaths().firstOrNull()?.path
                        ?: testNetwork.pathSeparator
                    val result = withContext(Dispatchers.Default) {
                        testNetwork.getList(rootPath)
                    }
                    if (result.isSuccess) content.inspect()
                    val message = if (result.isSuccess) {
                        AppStrings.ui_connection_successful
                    } else {
                        val reason = result.exceptionOrNull()?.message?.ifBlank { null }
                        reason?.let { AppStrings.ui_connection_failed_arg0.format(arg0 = it) }
                            ?: AppStrings.ui_connection_failed
                    }
                    snackbarHostState.showLatestSnackbar(message)
                } catch (t: Throwable) {
                    val reason = t.message?.ifBlank { null }
                    val message = when {
                        reason?.contains("Fail to fetch", ignoreCase = true) == true ||
                                reason?.contains("Failed to fetch", ignoreCase = true) == true ->
                            AppStrings.ui_connection_failed_browser_cannot_access_target_service_check_endpoint
                        else -> reason?.let { AppStrings.ui_connection_failed_arg0.format(arg0 = it) }
                            ?: AppStrings.ui_connection_failed
                    }
                    snackbarHostState.showLatestSnackbar(message)
                } finally {
                    testNetwork.disconnect()
                    isTesting = false
                }
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_add_network) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp)
                ) {
                    FloatingActionButton(
                        onClick = handleSaveClick,
                        content = {
                            Icon(Icons.Default.Save, contentDescription = AppStrings.ui_save)
                        },
                    )
                    TestConnectionButton(
                        isTesting = storageForm?.testing ?: isTesting,
                        onClick = handleTestClick,
                    )
                }
            },
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalSpacing = 16.dp,
                horizontalSpacing = 16.dp,
                floatingActionButtonPadding = GridListFabPadding
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EditableExposedDropdownMenu(
                        optionsUiState = StringListUiState(protocolOptions),
                        value = storageProtocolName?.let { storageProtocolLabel(NetworkProtocol.valueOf(it)) } ?: protocol.label,
                        readOnly = true,
                        onValueChange = { label ->
                            activeContent?.invalidate()
                            val storage = addedStorageProtocols.firstOrNull { storageProtocolLabel(it) == label }
                            storageProtocolName = storage?.name
                            if (storage == null) protocol = NetworkAddProtocol.fromLabel(label)
                        },
                        label = { Text(AppStrings.ui_agreement) },
                        optionContent = { label -> Text(label) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (storageForm != null) {
                    NetworkStorageForm(
                        protocol = storageForm.protocol, values = storageForm.values, onChange = { storageForm.values = it },
                        password = storageForm.password, onPassword = { storageForm.password = it },
                        token = storageForm.token, onToken = { storageForm.token = it },
                        accounts = storageForm.availableAccounts, libraries = storageForm.libraries,
                        authorization = storageForm.authorization, busy = storageForm.busy, error = storageForm.error,
                        onAuthorize = storageForm::authorize, onAuthorizeSharing = storageForm::authorizeSharing, onCancel = storageForm::cancelAuthorization,
                        onOpen = storageForm::openAuthorization, onRemove = storageForm::removeAuthorization,
                        onInstallSeafile = storageForm::installSeafile,
                    )
                } else when (protocol) {
                    NetworkAddProtocol.Share -> {
                        NetworkShareForm(
                            name = shareName,
                            onNameChange = { shareName = it },
                            address = shareAddress,
                            onAddressChange = { content.invalidate(); shareAddress = it },
                            addressError = shareError,
                            addressErrorMessage = shareErrorMessage,
                            resolvedBaseUrl = resolvedBaseUrl,
                            password = sharePassword,
                            onPasswordChange = { content.invalidate(); sharePassword = it },
                            passwordLabel = AppStrings.ui_access_password_optional,
                            parsedShare = parsedShare
                        )
                    }

                    NetworkAddProtocol.FTP -> {
                        val resolvedAddress = ftpParsedHost?.displayAddress.orEmpty()
                        NetworkFtpForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            host = networkHost,
                            onHostChange = { content.invalidate(); networkHost = it },
                            hostError = ftpHostError,
                            hostErrorMessage = ftpHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            username = networkUsername,
                            onUsernameChange = { content.invalidate(); networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { content.invalidate(); networkPassword = it },
                            passiveMode = ftpPassiveMode,
                            onPassiveModeChange = { content.invalidate(); ftpPassiveMode = it },
                            ftpsEnabled = ftpFtpsEnabled,
                            onFtpsEnabledChange = { content.invalidate(); ftpFtpsEnabled = it },
                            pathEncoding = ftpPathEncoding,
                            onPathEncodingChange = { content.invalidate(); ftpPathEncoding = it }
                        )
                    }

                    NetworkAddProtocol.SFTP -> {
                        val resolvedAddress = sftpParsedHost?.displayAddress.orEmpty()
                        NetworkSftpForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            host = networkHost,
                            onHostChange = { content.invalidate(); networkHost = it },
                            hostError = sftpHostError,
                            hostErrorMessage = sftpHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            authType = sftpAuthType,
                            onAuthTypeChange = { content.invalidate(); sftpAuthType = it },
                            username = networkUsername,
                            onUsernameChange = { content.invalidate(); networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { content.invalidate(); networkPassword = it },
                            privateKey = sftpPrivateKey,
                            onPrivateKeyChange = { content.invalidate(); sftpPrivateKey = it },
                            knownHosts = sftpKnownHosts,
                            onKnownHostsChange = { content.invalidate(); sftpKnownHosts = it }
                        )
                    }

                    NetworkAddProtocol.SMB -> {
                        val resolvedAddress = smbParsedHost?.displayAddress.orEmpty()
                        NetworkSmbForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            host = networkHost,
                            onHostChange = { content.invalidate(); networkHost = it },
                            hostError = smbHostError,
                            hostErrorMessage = smbHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            username = networkUsername,
                            onUsernameChange = { content.invalidate(); networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { content.invalidate(); networkPassword = it },
                            shareName = smbShareName,
                            onShareNameChange = { content.invalidate(); smbShareName = it },
                            shareNameError = smbShareError,
                            shareNameErrorMessage = smbShareErrorMessage,
                            resolvedShareName = smbParsedHost?.shareName.orEmpty(),
                            domain = smbDomain,
                            onDomainChange = { content.invalidate(); smbDomain = it }
                        )
                    }

                    NetworkAddProtocol.WebDav -> {
                        NetworkWebDavForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            host = networkHost,
                            onHostChange = { content.invalidate(); networkHost = it },
                            hostError = webDavHostError,
                            hostErrorMessage = webDavHostErrorMessage,
                            resolvedBaseUrl = webDavParsedHost?.displayAddress.orEmpty(),
                            authType = webDavAuthType,
                            onAuthTypeChange = { content.invalidate(); webDavAuthType = it },
                            username = networkUsername,
                            onUsernameChange = { content.invalidate(); networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { content.invalidate(); networkPassword = it },
                            token = webDavToken,
                            onTokenChange = { content.invalidate(); webDavToken = it },
                            tokenHeaderName = webDavTokenHeaderName,
                            onTokenHeaderNameChange = { content.invalidate(); webDavTokenHeaderName = it },
                            tokenPrefix = webDavTokenPrefix,
                            onTokenPrefixChange = { content.invalidate(); webDavTokenPrefix = it },
                            headers = webDavHeaders,
                            onHeaderKeyChange = { index, value ->
                                content.invalidate(); webDavHeaders = webDavHeaders.toMutableList().apply {
                                    this[index] = this[index].copy(key = value)
                                }
                            },
                            onHeaderValueChange = { index, value ->
                                content.invalidate(); webDavHeaders = webDavHeaders.toMutableList().apply {
                                    this[index] = this[index].copy(value = value)
                                }
                            },
                            onAddHeader = {
                                content.invalidate(); webDavHeaders = webDavHeaders + WebDavHeaderEntry("", "")
                            },
                            onRemoveHeader = { index ->
                                if (index in webDavHeaders.indices) {
                                    content.invalidate(); webDavHeaders = webDavHeaders.filterIndexed { currentIndex, _ ->
                                        currentIndex != index
                                    }
                                }
                            }
                        )
                    }

                    NetworkAddProtocol.S3 -> {
                        NetworkS3Form(
                            name = networkName,
                            onNameChange = { networkName = it },
                            endpoint = networkHost,
                            onEndpointChange = { content.invalidate(); networkHost = it },
                            endpointError = s3HostError,
                            endpointErrorMessage = s3HostErrorMessage,
                            resolvedEndpoint = s3ParsedEndpoint?.displayAddress.orEmpty(),
                            accessKeyId = networkUsername,
                            onAccessKeyIdChange = { content.invalidate(); networkUsername = it },
                            accessKeyIdError = s3AccessKeyError,
                            secretAccessKey = networkPassword,
                            onSecretAccessKeyChange = { content.invalidate(); networkPassword = it },
                            secretAccessKeyError = s3SecretKeyError,
                            sessionToken = s3SessionToken,
                            onSessionTokenChange = { content.invalidate(); s3SessionToken = it },
                            bucket = s3Bucket,
                            onBucketChange = { content.invalidate(); s3Bucket = it },
                            bucketError = s3BucketError,
                            region = s3Region,
                            onRegionChange = { content.invalidate(); s3Region = it },
                            regionError = s3RegionError,
                            forcePathStyle = s3ForcePathStyle,
                            onForcePathStyleChange = { content.invalidate(); s3ForcePathStyle = it }
                        )
                    }
                }
                if (activeContent?.supportedProtocol == true) item(span = { GridItemSpan(maxLineSpan) }) {
                    StorageContentSection(activeContent)
                }

            }
        }
    }
}

private enum class NetworkAddProtocol(
    val label: String,
    val port: Int?,
) {
    FTP("FTP", 21),
    SFTP("SFTP", 22),
    SMB("SMB", 445),
    S3("S3", null),
    WebDav("WebDav", 80),
    Share(AppStrings.ui_link_sharing, 1204);

    companion object {
        fun fromLabel(label: String): NetworkAddProtocol {
            return entries.firstOrNull { item -> item.label == label } ?: FTP
        }
    }
}

private fun List<WebDavHeaderEntry>.toHeaderMap(): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((key1, value) in this) {
        val key = key1.trim()
        if (key.isNotEmpty()) {
            result[key] = value
        }
    }
    return result
}
