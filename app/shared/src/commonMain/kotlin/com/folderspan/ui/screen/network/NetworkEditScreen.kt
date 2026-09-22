package com.folderspan.ui.screen.network

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.network.*
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.buttons.TestConnectionButton
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.network.form.*
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.parseNetworkAddress
import com.folderspan.utils.parseShareNetworkLink
import com.folderspan.utils.parseSmbAddress
import com.folderspan.utils.parseWebDavBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

class NetworkEditScreen(
    private val entry: NetworkEntry,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val networkState = koinInject<NetworkState>()
        val fileState = koinInject<FileState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val network = entry.network
        val currentExtras = remember(network) { network.extras }
        val formKey = entry.id
        var name by rememberSaveable(formKey) { mutableStateOf(network.name) }
        var host by rememberSaveable(formKey) { mutableStateOf(network.host) }
        var username by rememberSaveable(formKey) { mutableStateOf(network.username) }
        var password by rememberSaveable(formKey) { mutableStateOf(network.password) }
        val protocolName = network.protocol
        val defaultSeparator = remember(protocolName) {
            if (protocolName == NetworkProtocol.SMB.name) "\\" else "/"
        }

        var passiveMode by rememberSaveable(formKey) { mutableStateOf(currentExtras.ftp.passiveMode) }
        var ftpsEnabled by rememberSaveable(formKey) { mutableStateOf(currentExtras.ftp.ftpsEnabled) }
        var ftpPathEncoding by rememberSaveable(formKey) { mutableStateOf(currentExtras.ftp.pathEncoding) }
        var privateKey by rememberSaveable(formKey) { mutableStateOf(currentExtras.sftp.privateKey) }
        var sftpAuthType by rememberSaveable(formKey) {
            mutableStateOf(sftpAuthTypeFromPrivateKey(currentExtras.sftp.privateKey))
        }
        var knownHosts by rememberSaveable(formKey) { mutableStateOf(currentExtras.sftp.knownHosts) }
        var smbShare by rememberSaveable(formKey) { mutableStateOf(currentExtras.smb.share) }
        var smbDomain by rememberSaveable(formKey) { mutableStateOf(currentExtras.smb.domain) }
        var webDavAuthType by rememberSaveable(formKey) { mutableStateOf(currentExtras.webdav.authType) }
        var webDavToken by rememberSaveable(formKey) { mutableStateOf(currentExtras.webdav.token) }
        var webDavTokenHeaderName by rememberSaveable(formKey) { mutableStateOf(currentExtras.webdav.tokenHeaderName) }
        var webDavTokenPrefix by rememberSaveable(formKey) { mutableStateOf(currentExtras.webdav.tokenPrefix) }
        var webDavHeaders by rememberSaveable(
            formKey,
            stateSaver = WebDavHeaderEntryListSaver
        ) {
            mutableStateOf(
                currentExtras.webdav.headers.map { (key, value) ->
                    WebDavHeaderEntry(key, value)
                }
            )
        }
        var s3SessionToken by rememberSaveable(formKey) { mutableStateOf(currentExtras.s3.sessionToken) }
        var s3Bucket by rememberSaveable(formKey) { mutableStateOf(currentExtras.s3.bucket) }
        var s3Region by rememberSaveable(formKey) { mutableStateOf(currentExtras.s3.region) }
        var s3ForcePathStyle by rememberSaveable(formKey) { mutableStateOf(currentExtras.s3.forcePathStyle) }

        val nameError = name.isBlank()
        val hostEmpty = host.isBlank()
        val parsedShare = remember(host) { parseShareNetworkLink(host, 1204) }
        val ftpParsedHost = remember(host, protocolName) {
            if (protocolName == NetworkProtocol.FTP.name) parseNetworkAddress(host, 21) else null
        }
        val sftpParsedHost = remember(host, protocolName) {
            if (protocolName == NetworkProtocol.SFTP.name) parseNetworkAddress(host, 22) else null
        }
        val smbParsedHost = remember(host, protocolName) {
            if (protocolName == NetworkProtocol.SMB.name) parseSmbAddress(host, 445) else null
        }
        val webDavParsedHost = remember(host, protocolName) {
            if (protocolName == NetworkProtocol.WebDav.name) parseWebDavBaseUrl(host) else null
        }
        val s3ParsedEndpoint = remember(host, protocolName) {
            if (protocolName == NetworkProtocol.S3.name && host.isNotBlank()) {
                parseWebDavBaseUrl(host)
            } else {
                null
            }
        }
        val shareError = hostEmpty || parsedShare == null
        val resolvedBaseUrl = parsedShare?.baseUrl.orEmpty()
        val shareErrorMessage = when {
            hostEmpty -> AppStrings.ui_link_address_cannot_empty
            parsedShare == null -> AppStrings.ui_link_address_invalid
            else -> ""
        }
        val ftpHostError = hostEmpty || ftpParsedHost == null
        val sftpHostError = hostEmpty || sftpParsedHost == null
        val smbHostError = hostEmpty || smbParsedHost == null
        val resolvedSmbShareName = smbShare.trim().ifBlank { smbParsedHost?.shareName.orEmpty() }
        val smbShareError = resolvedSmbShareName.isBlank()
        val webDavHostError = hostEmpty || webDavParsedHost == null
        val s3HostError = hostEmpty || s3ParsedEndpoint == null
        val s3AccessKeyError = protocolName == NetworkProtocol.S3.name && username.isBlank()
        val s3SecretKeyError = protocolName == NetworkProtocol.S3.name && password.isBlank()
        val s3BucketError = protocolName == NetworkProtocol.S3.name && s3Bucket.isBlank()
        val s3RegionError = protocolName == NetworkProtocol.S3.name && s3Region.isBlank()
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
        val requiresSmbShare = protocolName == NetworkProtocol.SMB.name
        val requiresS3Fields = protocolName == NetworkProtocol.S3.name
        val hostError = when (protocolName) {
            NetworkProtocol.FTP.name -> ftpHostError
            NetworkProtocol.SFTP.name -> sftpHostError
            NetworkProtocol.SMB.name -> smbHostError
            NetworkProtocol.WebDav.name -> webDavHostError
            NetworkProtocol.S3.name -> s3HostError
            else -> hostEmpty
        }
        val canSaveStandard = !nameError && !hostError &&
                (!requiresSmbShare || !smbShareError) &&
                (!requiresS3Fields || !(s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError))
        val canSave = when (network) {
            is NetworkShare -> !shareError && name.isNotBlank()
            else -> canSaveStandard
        }
        val canTest = when (network) {
            is NetworkShare -> !shareError
            else -> !hostError &&
                    (!requiresSmbShare || !smbShareError) &&
                    (!requiresS3Fields || !(s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError))
        }
        var isTesting by remember { mutableStateOf(false) }

        val handleSaveClick: () -> Unit = save@{
            if (!canSave) return@save
            val separator = network.pathSeparator.ifBlank { defaultSeparator }
            val updated = when (network) {
                is NetworkShare -> {
                    if (nameError) return@save
                    val link = parsedShare ?: return@save
                    val resolvedPassword = password.trim().ifBlank { link.password.orEmpty() }
                    NetworkShare(
                        name = name.trim(),
                        baseUrl = link.baseUrl,
                        password = resolvedPassword,
                        pathSeparator = separator,
                        username = username.trim(),
                        pinned = network.pinned
                    )
                }

                else -> {
                    if (!canSaveStandard) return@save
                    val updatedExtras = when (protocolName) {
                        NetworkProtocol.FTP.name -> currentExtras.copy(
                            ftp = FtpDriveExtras(
                                passiveMode = passiveMode,
                                ftpsEnabled = ftpsEnabled,
                                pathEncoding = ftpPathEncoding
                            )
                        )

                        NetworkProtocol.SFTP.name -> currentExtras.copy(
                            sftp = SftpDriveExtras(
                                privateKey = sftpAuthType.selectedPrivateKey(privateKey),
                                knownHosts = knownHosts
                            )
                        )

                        NetworkProtocol.SMB.name -> currentExtras.copy(
                            smb = SmbDriveExtras(
                                share = resolvedSmbShareName,
                                domain = smbDomain.trim()
                            )
                        )

                        NetworkProtocol.WebDav.name -> currentExtras.copy(
                            webdav = WebDavDriveExtras(
                                authType = webDavAuthType,
                                token = webDavToken,
                                tokenHeaderName = webDavTokenHeaderName.trim().ifBlank { "Authorization" },
                                tokenPrefix = webDavTokenPrefix,
                                headers = webDavHeaders.toHeaderMap()
                            )
                        )

                        NetworkProtocol.S3.name -> currentExtras.copy(
                            s3 = S3DriveExtras(
                                bucket = s3Bucket.trim(),
                                region = s3Region.trim(),
                                endpoint = s3ParsedEndpoint?.normalizedBaseUrl ?: return@save,
                                sessionToken = s3SessionToken.trim(),
                                forcePathStyle = s3ForcePathStyle
                            )
                        )

                        else -> currentExtras
                    }
                    val resolvedHost = when (protocolName) {
                        NetworkProtocol.FTP.name -> ftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SFTP.name -> sftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SMB.name -> smbParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.WebDav.name -> webDavParsedHost?.normalizedBaseUrl ?: return@save
                        NetworkProtocol.S3.name -> s3ParsedEndpoint?.normalizedBaseUrl ?: return@save
                        else -> host.trim()
                    }
                    Network(
                        name = name.trim(),
                        pathSeparator = separator,
                        protocol = network.protocol,
                        host = resolvedHost,
                        username = username.trim(),
                        password = if (protocolName == NetworkProtocol.SFTP.name) {
                            sftpAuthType.selectedPassword(password)
                        } else {
                            password
                        },
                        pinned = network.pinned,
                        extras = updatedExtras
                    )
                }
            }
            val isCurrent = fileState.deskType.value === entry.network
            scope.launch {
                networkState.updateEntry(entry, updated)
                if (isCurrent) {
                    fileState.updateDesk(FileProtocol.Network, updated)
                }
                navigator.pop()
            }
        }

        val handleTestClick: () -> Unit = test@{
            if (!canTest || isTesting) return@test
            val separator = network.pathSeparator.ifBlank { defaultSeparator }
            val testNetwork = when (network) {
                is NetworkShare -> {
                    val link = parsedShare ?: return@test
                    NetworkShare(
                        name = name.trim().ifBlank { AppStrings.ui_temporary_connection },
                        baseUrl = link.baseUrl,
                        password = password.trim().ifBlank { link.password.orEmpty() },
                        pathSeparator = separator,
                        pinned = network.pinned
                    )
                }

                else -> {
                    val updatedExtras = when (protocolName) {
                        NetworkProtocol.FTP.name -> currentExtras.copy(
                            ftp = FtpDriveExtras(
                                passiveMode = passiveMode,
                                ftpsEnabled = ftpsEnabled,
                                pathEncoding = ftpPathEncoding
                            )
                        )

                        NetworkProtocol.SFTP.name -> currentExtras.copy(
                            sftp = SftpDriveExtras(
                                privateKey = sftpAuthType.selectedPrivateKey(privateKey),
                                knownHosts = knownHosts
                            )
                        )

                        NetworkProtocol.SMB.name -> currentExtras.copy(
                            smb = SmbDriveExtras(
                                share = resolvedSmbShareName,
                                domain = smbDomain.trim()
                            )
                        )

                        NetworkProtocol.WebDav.name -> currentExtras.copy(
                            webdav = WebDavDriveExtras(
                                authType = webDavAuthType,
                                token = webDavToken,
                                tokenHeaderName = webDavTokenHeaderName.trim().ifBlank { "Authorization" },
                                tokenPrefix = webDavTokenPrefix,
                                headers = webDavHeaders.toHeaderMap()
                            )
                        )

                        NetworkProtocol.S3.name -> currentExtras.copy(
                            s3 = S3DriveExtras(
                                bucket = s3Bucket.trim(),
                                region = s3Region.trim(),
                                endpoint = s3ParsedEndpoint?.normalizedBaseUrl ?: return@test,
                                sessionToken = s3SessionToken.trim(),
                                forcePathStyle = s3ForcePathStyle
                            )
                        )

                        else -> currentExtras
                    }
                    val resolvedHost = when (protocolName) {
                        NetworkProtocol.FTP.name -> ftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SFTP.name -> sftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SMB.name -> smbParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.WebDav.name -> webDavParsedHost?.normalizedBaseUrl ?: return@test
                        NetworkProtocol.S3.name -> s3ParsedEndpoint?.normalizedBaseUrl ?: return@test
                        else -> host.trim()
                    }
                    Network(
                        name = name.trim().ifBlank { AppStrings.ui_temporary_connection },
                        pathSeparator = separator,
                        protocol = network.protocol,
                        host = resolvedHost,
                        username = username.trim(),
                        password = if (protocolName == NetworkProtocol.SFTP.name) {
                            sftpAuthType.selectedPassword(password)
                        } else {
                            password
                        },
                        pinned = network.pinned,
                        extras = updatedExtras
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
                    title = { Text(AppStrings.ui_edit_network) },
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
            }
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
                when (network) {
                    is NetworkShare -> {
                        NetworkShareForm(
                            name = name,
                            onNameChange = { name = it },
                            nameError = nameError,
                            showNameHint = false,
                            address = host,
                            onAddressChange = { host = it },
                            addressError = shareError,
                            addressErrorMessage = shareErrorMessage,
                            resolvedBaseUrl = resolvedBaseUrl,
                            password = password,
                            onPasswordChange = { password = it },
                            passwordLabel = AppStrings.ui_access_password,
                            parsedShare = parsedShare
                        )
                    }

                    else -> {
                        when (protocolName) {
                            NetworkProtocol.FTP.name -> {
                                NetworkFtpForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    host = host,
                                    onHostChange = { host = it },
                                    hostError = hostError,
                                    hostErrorMessage = ftpHostErrorMessage,
                                    resolvedBaseUrl = ftpParsedHost?.displayAddress.orEmpty(),
                                    username = username,
                                    onUsernameChange = { username = it },
                                    password = password,
                                    onPasswordChange = { password = it },
                                    passiveMode = passiveMode,
                                    onPassiveModeChange = { passiveMode = it },
                                    ftpsEnabled = ftpsEnabled,
                                    onFtpsEnabledChange = { ftpsEnabled = it },
                                    pathEncoding = ftpPathEncoding,
                                    onPathEncodingChange = { ftpPathEncoding = it }
                                )
                            }

                            NetworkProtocol.SFTP.name -> {
                                NetworkSftpForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    host = host,
                                    onHostChange = { host = it },
                                    hostError = hostError,
                                    hostErrorMessage = sftpHostErrorMessage,
                                    resolvedBaseUrl = sftpParsedHost?.displayAddress.orEmpty(),
                                    authType = sftpAuthType,
                                    onAuthTypeChange = { sftpAuthType = it },
                                    username = username,
                                    onUsernameChange = { username = it },
                                    password = password,
                                    onPasswordChange = { password = it },
                                    privateKey = privateKey,
                                    onPrivateKeyChange = { privateKey = it },
                                    knownHosts = knownHosts,
                                    onKnownHostsChange = { knownHosts = it }
                                )
                            }

                            NetworkProtocol.SMB.name -> {
                                NetworkSmbForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    host = host,
                                    onHostChange = { host = it },
                                    hostError = hostError,
                                    hostErrorMessage = smbHostErrorMessage,
                                    resolvedBaseUrl = smbParsedHost?.displayAddress.orEmpty(),
                                    username = username,
                                    onUsernameChange = { username = it },
                                    password = password,
                                    onPasswordChange = { password = it },
                                    shareName = smbShare,
                                    onShareNameChange = { smbShare = it },
                                    shareNameError = smbShareError,
                                    shareNameErrorMessage = smbShareErrorMessage,
                                    resolvedShareName = smbParsedHost?.shareName.orEmpty(),
                                    domain = smbDomain,
                                    onDomainChange = { smbDomain = it }
                                )
                            }

                            NetworkProtocol.WebDav.name -> {
                                NetworkWebDavForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    host = host,
                                    onHostChange = { host = it },
                                    hostError = hostError,
                                    hostErrorMessage = webDavHostErrorMessage,
                                    resolvedBaseUrl = webDavParsedHost?.displayAddress.orEmpty(),
                                    authType = webDavAuthType,
                                    onAuthTypeChange = { webDavAuthType = it },
                                    username = username,
                                    onUsernameChange = { username = it },
                                    password = password,
                                    onPasswordChange = { password = it },
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

                            NetworkProtocol.S3.name -> {
                                NetworkS3Form(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    endpoint = host,
                                    onEndpointChange = { host = it },
                                    endpointError = s3HostError,
                                    endpointErrorMessage = s3HostErrorMessage,
                                    resolvedEndpoint = s3ParsedEndpoint?.displayAddress.orEmpty(),
                                    accessKeyId = username,
                                    onAccessKeyIdChange = { username = it },
                                    accessKeyIdError = s3AccessKeyError,
                                    secretAccessKey = password,
                                    onSecretAccessKeyChange = { password = it },
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

                            else -> {
                                NetworkStandardForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    nameError = nameError,
                                    host = host,
                                    onHostChange = { host = it },
                                    hostError = hostError,
                                    username = username,
                                    onUsernameChange = { username = it },
                                    password = password,
                                    onPasswordChange = { password = it }
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
internal fun NetworkEditSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
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
