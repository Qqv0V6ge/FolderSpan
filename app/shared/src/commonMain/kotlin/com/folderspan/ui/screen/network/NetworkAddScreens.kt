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
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

class NetworkAddEntryScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val networkState = koinInject<NetworkState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        val initialProtocol = remember {
            if (PlatformType == DeviceType.JS) NetworkAddProtocol.Share else NetworkAddProtocol.FTP
        }
        var protocol by rememberSaveable { mutableStateOf(initialProtocol) }
        val protocolOptions = remember { NetworkAddProtocol.entries.map { item -> item.label } }

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

        var saveToDatabase by rememberSaveable { mutableStateOf(true) }
        var isTesting by remember { mutableStateOf(false) }

        val nameError = networkName.isBlank()
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
            NetworkAddProtocol.FTP -> !(nameError || ftpHostError)
            NetworkAddProtocol.SFTP -> !(nameError || sftpHostError)
            NetworkAddProtocol.SMB -> !(nameError || smbHostError || smbShareError)
            NetworkAddProtocol.WebDav -> !(nameError || webDavHostError)
            NetworkAddProtocol.S3 -> !(nameError || s3HostError || s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError)
        }
        val canTest = when (protocol) {
            NetworkAddProtocol.Share -> !shareError
            NetworkAddProtocol.FTP -> !ftpHostError
            NetworkAddProtocol.SFTP -> !sftpHostError
            NetworkAddProtocol.SMB -> !(smbHostError || smbShareError)
            NetworkAddProtocol.WebDav -> !webDavHostError
            NetworkAddProtocol.S3 -> !(s3HostError || s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError)
        }

        val handleSaveClick: () -> Unit = save@{
            if (!canSave) return@save
            when (protocol) {
                NetworkAddProtocol.Share -> {
                    val link = parsedShare ?: return@save
                    val resolvedName = shareName.trim().ifBlank { link.hostLabel }
                    val resolvedPassword = sharePassword.trim().ifBlank { link.password.orEmpty() }
                    val shareNetwork = NetworkShare(
                        name = resolvedName,
                        baseUrl = link.baseUrl,
                        password = resolvedPassword
                    )
                    scope.launch {
                        networkState.addNetwork(shareNetwork, saveToDatabase)
                        navigator.pop()
                    }
                }

                NetworkAddProtocol.FTP -> {
                    val parsedHost = ftpParsedHost ?: return@save
                    if (nameError || ftpHostError) return@save
                    val network = Network(
                        name = networkName.trim(),
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
                    scope.launch {
                        networkState.addNetwork(network, saveToDatabase)
                        navigator.pop()
                    }
                }

                NetworkAddProtocol.SFTP -> {
                    val parsedHost = sftpParsedHost ?: return@save
                    if (nameError || sftpHostError) return@save
                    val network = Network(
                        name = networkName.trim(),
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
                    scope.launch {
                        networkState.addNetwork(network, saveToDatabase)
                        navigator.pop()
                    }
                }

                NetworkAddProtocol.SMB -> {
                    val parsedHost = smbParsedHost ?: return@save
                    if (nameError || smbHostError || smbShareError) return@save
                    val network = Network(
                        name = networkName.trim(),
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
                    scope.launch {
                        networkState.addNetwork(network, saveToDatabase)
                        navigator.pop()
                    }
                }

                NetworkAddProtocol.WebDav -> {
                    if (nameError || webDavHostError) return@save
                    val resolvedHost = webDavParsedHost.normalizedBaseUrl
                    val network = Network(
                        name = networkName.trim(),
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
                    scope.launch {
                        networkState.addNetwork(network, saveToDatabase)
                        navigator.pop()
                    }
                }

                NetworkAddProtocol.S3 -> {
                    if (nameError || s3HostError || s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError) {
                        return@save
                    }
                    val resolvedEndpoint = s3ParsedEndpoint.normalizedBaseUrl
                    val network = Network(
                        name = networkName.trim(),
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
                    scope.launch {
                        networkState.addNetwork(network, saveToDatabase)
                        navigator.pop()
                    }
                }
            }
        }

        val handleTestClick: () -> Unit = test@{
            if (!canTest || isTesting) return@test
            val testNetwork = when (protocol) {
                NetworkAddProtocol.Share -> {
                    val link = parsedShare ?: return@test
                    NetworkShare(
                        name = shareName.trim().ifBlank { AppStrings.ui_temporary_connection },
                        baseUrl = link.baseUrl,
                        password = sharePassword.trim().ifBlank { link.password.orEmpty() }
                    )
                }
                NetworkAddProtocol.FTP -> {
                    val parsedHost = ftpParsedHost ?: return@test
                    Network(
                        name = networkName.trim().ifBlank { AppStrings.ui_temporary_connection },
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
                    val parsedHost = sftpParsedHost ?: return@test
                    Network(
                        name = networkName.trim().ifBlank { AppStrings.ui_temporary_connection },
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
                    val parsedHost = smbParsedHost ?: return@test
                    Network(
                        name = networkName.trim().ifBlank { AppStrings.ui_temporary_connection },
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
                    val resolvedHost = webDavParsedHost?.normalizedBaseUrl ?: return@test
                    Network(
                        name = networkName.trim().ifBlank { AppStrings.ui_temporary_connection },
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
                    val parsedEndpoint = s3ParsedEndpoint ?: return@test
                    val resolvedEndpoint = parsedEndpoint.normalizedBaseUrl
                    Network(
                        name = networkName.trim().ifBlank { AppStrings.ui_temporary_connection },
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
            scope.launch {
                isTesting = true
                try {
                    val rootPath = testNetwork.getRootPaths().firstOrNull()?.path
                        ?: testNetwork.pathSeparator
                    val result = withContext(Dispatchers.Default) {
                        testNetwork.getList(rootPath)
                    }
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
                            Icon(Icons.Default.Save, contentDescription = null)
                        },
                    )
                    TestConnectionButton(
                        isTesting = isTesting,
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
                        value = protocol.label,
                        readOnly = true,
                        onValueChange = { label -> protocol = NetworkAddProtocol.fromLabel(label) },
                        label = { Text(AppStrings.ui_agreement) },
                        optionContent = { label -> Text(label) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                when (protocol) {
                    NetworkAddProtocol.Share -> {
                        NetworkShareForm(
                            name = shareName,
                            onNameChange = { shareName = it },
                            nameError = false,
                            showNameHint = true,
                            address = shareAddress,
                            onAddressChange = { shareAddress = it },
                            addressError = shareError,
                            addressErrorMessage = shareErrorMessage,
                            resolvedBaseUrl = resolvedBaseUrl,
                            password = sharePassword,
                            onPasswordChange = { sharePassword = it },
                            passwordLabel = AppStrings.ui_access_password_optional,
                            parsedShare = parsedShare
                        )
                    }

                    NetworkAddProtocol.FTP -> {
                        val resolvedAddress = ftpParsedHost?.displayAddress.orEmpty()
                        NetworkFtpForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            nameError = nameError,
                            host = networkHost,
                            onHostChange = { networkHost = it },
                            hostError = ftpHostError,
                            hostErrorMessage = ftpHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            username = networkUsername,
                            onUsernameChange = { networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { networkPassword = it },
                            passiveMode = ftpPassiveMode,
                            onPassiveModeChange = { ftpPassiveMode = it },
                            ftpsEnabled = ftpFtpsEnabled,
                            onFtpsEnabledChange = { ftpFtpsEnabled = it },
                            pathEncoding = ftpPathEncoding,
                            onPathEncodingChange = { ftpPathEncoding = it }
                        )
                    }

                    NetworkAddProtocol.SFTP -> {
                        val resolvedAddress = sftpParsedHost?.displayAddress.orEmpty()
                        NetworkSftpForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            nameError = nameError,
                            host = networkHost,
                            onHostChange = { networkHost = it },
                            hostError = sftpHostError,
                            hostErrorMessage = sftpHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            authType = sftpAuthType,
                            onAuthTypeChange = { sftpAuthType = it },
                            username = networkUsername,
                            onUsernameChange = { networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { networkPassword = it },
                            privateKey = sftpPrivateKey,
                            onPrivateKeyChange = { sftpPrivateKey = it },
                            knownHosts = sftpKnownHosts,
                            onKnownHostsChange = { sftpKnownHosts = it }
                        )
                    }

                    NetworkAddProtocol.SMB -> {
                        val resolvedAddress = smbParsedHost?.displayAddress.orEmpty()
                        NetworkSmbForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            nameError = nameError,
                            host = networkHost,
                            onHostChange = { networkHost = it },
                            hostError = smbHostError,
                            hostErrorMessage = smbHostErrorMessage,
                            resolvedBaseUrl = resolvedAddress,
                            username = networkUsername,
                            onUsernameChange = { networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { networkPassword = it },
                            shareName = smbShareName,
                            onShareNameChange = { smbShareName = it },
                            shareNameError = smbShareError,
                            shareNameErrorMessage = smbShareErrorMessage,
                            resolvedShareName = smbParsedHost?.shareName.orEmpty(),
                            domain = smbDomain,
                            onDomainChange = { smbDomain = it }
                        )
                    }

                    NetworkAddProtocol.WebDav -> {
                        NetworkWebDavForm(
                            name = networkName,
                            onNameChange = { networkName = it },
                            nameError = nameError,
                            host = networkHost,
                            onHostChange = { networkHost = it },
                            hostError = webDavHostError,
                            hostErrorMessage = webDavHostErrorMessage,
                            resolvedBaseUrl = webDavParsedHost?.displayAddress.orEmpty(),
                            authType = webDavAuthType,
                            onAuthTypeChange = { webDavAuthType = it },
                            username = networkUsername,
                            onUsernameChange = { networkUsername = it },
                            password = networkPassword,
                            onPasswordChange = { networkPassword = it },
                            token = webDavToken,
                            onTokenChange = { webDavToken = it },
                            tokenHeaderName = webDavTokenHeaderName,
                            onTokenHeaderNameChange = { webDavTokenHeaderName = it },
                            tokenPrefix = webDavTokenPrefix,
                            onTokenPrefixChange = { webDavTokenPrefix = it },
                            headers = webDavHeaders,
                            onHeaderKeyChange = { index, value ->
                                webDavHeaders = webDavHeaders.toMutableList().apply {
                                    this[index] = this[index].copy(key = value)
                                }
                            },
                            onHeaderValueChange = { index, value ->
                                webDavHeaders = webDavHeaders.toMutableList().apply {
                                    this[index] = this[index].copy(value = value)
                                }
                            },
                            onAddHeader = {
                                webDavHeaders = webDavHeaders + WebDavHeaderEntry("", "")
                            },
                            onRemoveHeader = { index ->
                                if (index in webDavHeaders.indices) {
                                    webDavHeaders = webDavHeaders.filterIndexed { currentIndex, _ ->
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
                            nameError = nameError,
                            endpoint = networkHost,
                            onEndpointChange = { networkHost = it },
                            endpointError = s3HostError,
                            endpointErrorMessage = s3HostErrorMessage,
                            resolvedEndpoint = s3ParsedEndpoint?.displayAddress.orEmpty(),
                            accessKeyId = networkUsername,
                            onAccessKeyIdChange = { networkUsername = it },
                            accessKeyIdError = s3AccessKeyError,
                            secretAccessKey = networkPassword,
                            onSecretAccessKeyChange = { networkPassword = it },
                            secretAccessKeyError = s3SecretKeyError,
                            sessionToken = s3SessionToken,
                            onSessionTokenChange = { s3SessionToken = it },
                            bucket = s3Bucket,
                            onBucketChange = { s3Bucket = it },
                            bucketError = s3BucketError,
                            region = s3Region,
                            onRegionChange = { s3Region = it },
                            regionError = s3RegionError,
                            forcePathStyle = s3ForcePathStyle,
                            onForcePathStyleChange = { s3ForcePathStyle = it }
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(
                        headlineContent = { Text(AppStrings.ui_save_database) },
                        trailingContent = {
                            Switch(
                                checked = saveToDatabase,
                                onCheckedChange = { saveToDatabase = it }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
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
