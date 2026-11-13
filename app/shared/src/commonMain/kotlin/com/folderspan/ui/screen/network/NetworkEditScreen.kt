package com.folderspan.ui.screen.network

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import kotlinx.coroutines.CoroutineExceptionHandler
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
        val notificationScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope {
            CoroutineExceptionHandler { _, error ->
                notificationScope.launch { snackbarHostState.showLatestSnackbar(error.message ?: AppStrings.ui_connection_failed) }
            }
        }
        val network = entry.network
        if (network.protocol in setOf("GoogleDrive", "Dropbox") &&
            addedStorageProtocols.none { it.name == network.protocol }
        ) {
            AppScaffold(topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_edit_network) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    },
                )
            }) { padding ->
                Text(
                    AppStrings.storage_official_authorization_pro_only,
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            }
            return
        }
        val currentExtras = remember(network) { network.extras }
        val formKey = entry.id
        val usesStorageForm = addedStorageProtocols.any { it.name == network.protocol }
        var name by rememberSaveable(formKey) { mutableStateOf(network.name) }
        var host by rememberSaveable(formKey) { mutableStateOf(network.host) }
        var username by rememberSaveable(formKey) { mutableStateOf(network.username) }
        var password by rememberSaveable(formKey) { mutableStateOf(if (usesStorageForm) "" else network.password) }
        val protocolName = network.protocol
        val storageForm = if (usesStorageForm) {
            rememberStorageFormState(protocolName, entry, snackbarHostState) { updated ->
                val current = fileState.deskType.value === entry.network
                networkState.updateEntry(entry, updated)
                networkState.entries.firstOrNull { it.id == entry.id }?.let { networkState.updatePersisted(it, true) }
                if (current) fileState.updateDesk(FileProtocol.Network, updated)
                navigator.pop()
            }
        } else null
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
        val canSaveStandard = !hostError &&
                (!requiresSmbShare || !smbShareError) &&
                (!requiresS3Fields || !(s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError))
        val canSave = when (network) {
            is NetworkShare -> !shareError
            else -> canSaveStandard
        }
        val canTest = when (network) {
            is NetworkShare -> !shareError
            else -> !hostError &&
                    (!requiresSmbShare || !smbShareError) &&
                    (!requiresS3Fields || !(s3AccessKeyError || s3SecretKeyError || s3BucketError || s3RegionError))
        }
        var isTesting by remember { mutableStateOf(false) }

        fun currentNetwork(): Network? {
            if (!canSave) return null
            val separator = network.pathSeparator.ifBlank { defaultSeparator }
            return when (network) {
                is NetworkShare -> {
                    val link = parsedShare ?: return null
                    val resolvedPassword = password.trim().ifBlank { link.password.orEmpty() }
                    NetworkShare(
                        name = name.trim().ifBlank { link.hostLabel },
                        baseUrl = link.baseUrl,
                        password = resolvedPassword,
                        pathSeparator = separator,
                        username = username.trim(),
                        pinned = network.pinned
                    )
                }

                else -> {
                    if (!canSaveStandard) return null
                    val updatedExtras = when (protocolName) {
                        NetworkProtocol.FTP.name -> currentExtras.copy(contentEncryption = null,
                            ftp = FtpDriveExtras(
                                passiveMode = passiveMode,
                                ftpsEnabled = ftpsEnabled,
                                pathEncoding = ftpPathEncoding
                            )
                        )

                        NetworkProtocol.SFTP.name -> currentExtras.copy(contentEncryption = null,
                            sftp = SftpDriveExtras(
                                privateKey = sftpAuthType.selectedPrivateKey(privateKey),
                                knownHosts = knownHosts
                            )
                        )

                        NetworkProtocol.SMB.name -> currentExtras.copy(contentEncryption = null,
                            smb = SmbDriveExtras(
                                share = resolvedSmbShareName,
                                domain = smbDomain.trim()
                            )
                        )

                        NetworkProtocol.WebDav.name -> currentExtras.copy(contentEncryption = null,
                            webdav = WebDavDriveExtras(
                                authType = webDavAuthType,
                                token = webDavToken,
                                tokenHeaderName = webDavTokenHeaderName.trim().ifBlank { "Authorization" },
                                tokenPrefix = webDavTokenPrefix,
                                headers = webDavHeaders.toHeaderMap()
                            )
                        )

                        NetworkProtocol.S3.name -> currentExtras.copy(contentEncryption = null,
                            s3 = S3DriveExtras(
                                bucket = s3Bucket.trim(),
                                region = s3Region.trim(),
                                endpoint = s3ParsedEndpoint?.normalizedBaseUrl ?: return null,
                                sessionToken = s3SessionToken.trim(),
                                forcePathStyle = s3ForcePathStyle
                            )
                        )

                        else -> currentExtras.copy(contentEncryption = null)
                    }
                    val resolvedHost = when (protocolName) {
                        NetworkProtocol.FTP.name -> ftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SFTP.name -> sftpParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.SMB.name -> smbParsedHost?.normalizedHost ?: host.trim()
                        NetworkProtocol.WebDav.name -> webDavParsedHost?.normalizedBaseUrl ?: return null
                        NetworkProtocol.S3.name -> s3ParsedEndpoint?.normalizedBaseUrl ?: return null
                        else -> host.trim()
                    }
                    Network(
                        name = name.trim().ifBlank { defaultNetworkName(protocolName, host) },
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
        }
        val content = rememberStorageContentFormState(protocolName,
            if (storageForm == null) currentNetwork() else null, network)
        val activeContent = storageForm?.content ?: content
        var isSaving by remember { mutableStateOf(false) }
        suspend fun persist(updated: Network) {
            val isCurrent = fileState.deskType.value === entry.network
            networkState.updateEntry(entry, updated)
            networkState.entries.firstOrNull { it.id == entry.id }?.let { networkState.updatePersisted(it, true) }
            if (isCurrent) fileState.updateDesk(FileProtocol.Network, updated)
        }

        val handleSaveClick: () -> Unit = save@{
            if (storageForm != null) {
                storageForm.save()
                return@save
            }
            if (isSaving) return@save
            val updated = currentNetwork() ?: return@save
            isSaving = true
            scope.launch {
                try {
                    if (content.supportedProtocol) content.save(updated, ::persist) else persist(updated)
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
                            Icon(Icons.Default.Save, contentDescription = AppStrings.ui_save)
                        },

                    )
                    TestConnectionButton(
                        isTesting = storageForm?.testing ?: isTesting,
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
                        existingNetwork = network,
                    )

                } else when (network) {
                    is NetworkShare -> {
                        NetworkShareForm(
                            name = name,
                            onNameChange = { name = it },
                            address = host,
                            onAddressChange = { content.invalidate(); host = it },
                            addressError = shareError,
                            addressErrorMessage = shareErrorMessage,
                            resolvedBaseUrl = resolvedBaseUrl,
                            password = password,
                            onPasswordChange = { content.invalidate(); password = it },
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
                                    host = host,
                                    onHostChange = { content.invalidate(); host = it },
                                    hostError = hostError,
                                    hostErrorMessage = ftpHostErrorMessage,
                                    resolvedBaseUrl = ftpParsedHost?.displayAddress.orEmpty(),
                                    username = username,
                                    onUsernameChange = { content.invalidate(); username = it },
                                    password = password,
                                    onPasswordChange = { content.invalidate(); password = it },
                                    passiveMode = passiveMode,
                                    onPassiveModeChange = { content.invalidate(); passiveMode = it },
                                    ftpsEnabled = ftpsEnabled,
                                    onFtpsEnabledChange = { content.invalidate(); ftpsEnabled = it },
                                    pathEncoding = ftpPathEncoding,
                                    onPathEncodingChange = { content.invalidate(); ftpPathEncoding = it }
                                )
                            }

                            NetworkProtocol.SFTP.name -> {
                                NetworkSftpForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    host = host,
                                    onHostChange = { content.invalidate(); host = it },
                                    hostError = hostError,
                                    hostErrorMessage = sftpHostErrorMessage,
                                    resolvedBaseUrl = sftpParsedHost?.displayAddress.orEmpty(),
                                    authType = sftpAuthType,
                                    onAuthTypeChange = { content.invalidate(); sftpAuthType = it },
                                    username = username,
                                    onUsernameChange = { content.invalidate(); username = it },
                                    password = password,
                                    onPasswordChange = { content.invalidate(); password = it },
                                    privateKey = privateKey,
                                    onPrivateKeyChange = { content.invalidate(); privateKey = it },
                                    knownHosts = knownHosts,
                                    onKnownHostsChange = { content.invalidate(); knownHosts = it }
                                )
                            }

                            NetworkProtocol.SMB.name -> {
                                NetworkSmbForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    host = host,
                                    onHostChange = { content.invalidate(); host = it },
                                    hostError = hostError,
                                    hostErrorMessage = smbHostErrorMessage,
                                    resolvedBaseUrl = smbParsedHost?.displayAddress.orEmpty(),
                                    username = username,
                                    onUsernameChange = { content.invalidate(); username = it },
                                    password = password,
                                    onPasswordChange = { content.invalidate(); password = it },
                                    shareName = smbShare,
                                    onShareNameChange = { content.invalidate(); smbShare = it },
                                    shareNameError = smbShareError,
                                    shareNameErrorMessage = smbShareErrorMessage,
                                    resolvedShareName = smbParsedHost?.shareName.orEmpty(),
                                    domain = smbDomain,
                                    onDomainChange = { content.invalidate(); smbDomain = it }
                                )
                            }

                            NetworkProtocol.WebDav.name -> {
                                NetworkWebDavForm(
                                    name = name,
                                    onNameChange = { name = it },
                                    host = host,
                                    onHostChange = { content.invalidate(); host = it },
                                    hostError = hostError,
                                    hostErrorMessage = webDavHostErrorMessage,
                                    resolvedBaseUrl = webDavParsedHost?.displayAddress.orEmpty(),
                                    authType = webDavAuthType,
                                    onAuthTypeChange = { content.invalidate(); webDavAuthType = it },
                                    username = username,
                                    onUsernameChange = { content.invalidate(); username = it },
                                    password = password,
                                    onPasswordChange = { content.invalidate(); password = it },
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

                            NetworkProtocol.S3.name -> {
                                NetworkS3Form(
                                    name = name,
                                    onNameChange = { name = it },
                                    endpoint = host,
                                    onEndpointChange = { content.invalidate(); host = it },
                                    endpointError = s3HostError,
                                    endpointErrorMessage = s3HostErrorMessage,
                                    resolvedEndpoint = s3ParsedEndpoint?.displayAddress.orEmpty(),
                                    accessKeyId = username,
                                    onAccessKeyIdChange = { content.invalidate(); username = it },
                                    accessKeyIdError = s3AccessKeyError,
                                    secretAccessKey = password,
                                    onSecretAccessKeyChange = { content.invalidate(); password = it },
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

                            else -> {
                                NetworkStandardForm(
                                    protocol = protocolName,
                                    name = name,
                                    onNameChange = { name = it },
                                    host = host,
                                    onHostChange = { content.invalidate(); host = it },
                                    hostError = hostError,
                                    username = username,
                                    onUsernameChange = { content.invalidate(); username = it },
                                    password = password,
                                    onPasswordChange = { content.invalidate(); password = it }
                                )
                            }
                        }
                    }
                }
                if (activeContent?.supportedProtocol == true) item(span = { GridItemSpan(maxLineSpan) }) {
                    StorageContentSection(activeContent)
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
