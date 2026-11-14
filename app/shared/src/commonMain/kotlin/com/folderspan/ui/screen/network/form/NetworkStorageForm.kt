package com.folderspan.ui.screen.network.form

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.network.*
import com.folderspan.openUrl
import com.folderspan.officialStorageProtocols
import com.folderspan.service.network.*
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.utils.parseNetworkAddress
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import strings.AppStrings
import kotlin.uuid.Uuid

internal val addedStorageProtocols = listOf(NetworkProtocol.NFS, NetworkProtocol.HttpDirectory) +
    officialStorageProtocols + listOf(NetworkProtocol.Nextcloud, NetworkProtocol.Seafile)
internal fun storageProtocolLabel(protocol: NetworkProtocol): String = when (protocol) {
    NetworkProtocol.HttpDirectory -> AppStrings.storage_http_directory
    NetworkProtocol.GoogleDrive -> "Google Drive"
    else -> protocol.name
}
private fun storageKind(protocol: String) = when (protocol) {
    "GoogleDrive" -> StorageAccountKind.GoogleDrive
    "Dropbox" -> StorageAccountKind.Dropbox
    "Nextcloud" -> StorageAccountKind.Nextcloud
    "Seafile" -> StorageAccountKind.Seafile
    else -> null
}

@Serializable
internal data class StorageFormValues(
    val name: String = "", val server: String = "", val username: String = "",
    val root: String = "", val library: String = "",
    val account: String = "", val connection: String = "",
    val nfsVersion: Int = 4, val export: String = "/", val uid: String = "0", val gid: String = "0",
    val groups: String = "", val mountPort: String = "0", val repoToken: Boolean = false, val readOnly: Boolean = false,
) {
    fun validationErrors(protocol: String): Map<String, String> = buildMap {
        val host = server.trim()
        if (protocol in setOf("NFS", "HttpDirectory", "Nextcloud", "Seafile")) {
            val valid = if (protocol == "NFS") parseNetworkAddress(host, 2049) != null else runCatching {
                val url = Url(host)
                url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
                    (host.startsWith("http://", true) || host.startsWith("https://", true)) &&
                    url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                    url.parameters.isEmpty() && url.fragment.isEmpty()
            }.getOrDefault(false)
            if (host.isEmpty()) put("server", AppStrings.ui_link_address_cannot_empty)
            else if (!valid) put("server", AppStrings.ui_link_address_invalid)
        }
        if (protocol == "NFS") {
            if (nfsVersion !in setOf(3, 4)) put("version", AppStrings.storage_invalid_nfs)
            if (!export.trim().startsWith('/')) put("export", AppStrings.storage_nfs_export_hint)
            for ((field, value) in listOf("uid" to uid, "gid" to gid)) {
                if (value.toIntOrNull()?.takeIf { it >= 0 } == null) put(field, AppStrings.storage_nfs_identity_hint)
            }
            val gids = groups.split(',')
            if (groups.isNotBlank() && (gids.size > 16 || gids.any { it.trim().toIntOrNull()?.takeIf { id -> id >= 0 } == null })) {
                put("groups", AppStrings.storage_nfs_groups_hint)
            }
            if (nfsVersion == 3 && mountPort.toIntOrNull()?.takeIf { it in 0..65535 } == null) {
                put("mountPort", AppStrings.storage_nfs_mount_port_hint)
            }
        }
        if (storageKind(protocol) != null && (account.isBlank() || connection.isBlank())) {
            put("account", AppStrings.storage_authorize_first)
        }
        if (protocol == "Seafile" && !library.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            put("library", AppStrings.storage_select_library)
        }
    }

    fun network(protocol: String, password: String, pinned: Boolean = false): Network {
        val errors = validationErrors(protocol)
        require(errors.isEmpty()) { errors.values.first() }
        var host = server.trim()
        var extras = NetworkDriveExtras()
        if (protocol == NetworkProtocol.NFS.name) {
            val parsed = checkNotNull(parseNetworkAddress(host, 2049))
            val gids = groups.split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }
            val mount = if (nfsVersion == 3) mountPort.toInt() else 0
            extras = extras.copy(nfs = NfsDriveExtras(nfsVersion, export.trim(), uid.toInt(), gid.toInt(), gids, mount))
            host = parsed.normalizedHost
        }
        if (storageKind(protocol) != null) {
            extras = extras.copy(cloud = CloudDriveExtras(accountId = account, rootId = root, libraryId = library, connectionId = connection, seafileRepoToken = repoToken, readOnly = readOnly))
        }
        return Network(name.trim().ifBlank { defaultNetworkName(protocol, server) }, "/", protocol, host, if (storageKind(protocol) == null) username else "",
            if (storageKind(protocol) == null) password else "", pinned, extras)
    }
}

private val storageFormSaver = Saver<StorageFormValues, String>(
    save = { Json.encodeToString(it) }, restore = { Json.decodeFromString<StorageFormValues>(it) },
)

@Stable
internal class StorageFormState(
    val protocol: String,
    private val formState: MutableState<StorageFormValues>,
    private val passwordState: MutableState<String>,
    private val pinned: Boolean,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val networkFactory: (StorageFormValues, String, Boolean) -> Network = { values, password, pinned ->
        values.network(protocol, password, pinned)
    },
    private val onSave: suspend (Network) -> Unit,
) {
    var content: StorageContentFormState? = null
    var values: StorageFormValues
        get() = formState.value
        set(value) {
            formState.value = value
        }
    var password: String
        get() = passwordState.value
        set(value) {
            passwordState.value = value
        }
    var token by mutableStateOf("")
    var accounts by mutableStateOf(emptyList<StorageAccount>())
    var libraries by mutableStateOf(emptyList<StorageLibrary>())
    var authorization by mutableStateOf<StorageAuthorizationStart?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var testing by mutableStateOf(false)
    val kind = storageKind(protocol)
    val availableAccounts: List<StorageAccount>
        get() = accounts.filter { it.kind == kind && (it.server.isEmpty() || it.server == values.server.trimEnd('/')) }

    private fun launchAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: AppStrings.ui_connection_failed }
            finally { busy = false }
        }
    }

    fun save() = launchAction {
        val network = networkFactory(values, password, pinned)
        val draft = content
        if (draft?.supportedProtocol == true) draft.save(network, onSave) else onSave(network)
    }

    fun testConnection() = launchAction {
        val network = networkFactory(values, password, pinned)
        testing = true
        try {
            withContext(Dispatchers.Default) { network.getList("/").getOrThrow() }
            content?.inspect()
            snackbar.showLatestSnackbar(AppStrings.ui_connection_successful)
        } finally {
            network.close()
            testing = false
        }
    }

    fun authorize(replace: Boolean) = beginAuthorization(replace, emptySet())

    fun authorizeSharing() = beginAuthorization(true, setOf(StorageAuthorizationPermission.SHARING))

    private fun beginAuthorization(replace: Boolean, permissions: Set<StorageAuthorizationPermission>) {
        if (authorization != null) return
        launchAction {
            values.validationErrors(protocol)["server"]?.let { error(it) }
            authorization = StorageAccountPlatform.begin(StorageAuthorizationRequest(
                protocol, values.server, if (replace) values.account else null, permissions))
        }
    }

    fun cancelAuthorization() { authorization = null }
    fun openAuthorization() { authorization?.let { openUrl(it.loginUrl) } }

    fun removeAuthorization() = launchAction {
        val removed = values.account
        try { StorageAccountPlatform.remove(removed) } finally {
            values = values.copy(account = "", library = "")
            accounts = runCatching { StorageAccountPlatform.accounts() }.getOrElse { accounts.filter { it.id != removed } }
        }
    }

    fun installSeafile() {
        val supplied = token
        launchAction {
            values.validationErrors(protocol)["server"]?.let { error(it) }
            require(supplied.isNotBlank()) { AppStrings.storage_seafile_token_hint }
            if (values.repoToken) values.validationErrors(protocol)["library"]?.let { error(it) }
            token = ""
            val account = StorageAccountPlatform.installSeafile(SeafileAuthorizationRequest(values.server, supplied, values.repoToken, values.library))
            accounts = StorageAccountPlatform.accounts()
            values = values.copy(account = account.id, library = account.libraryId.ifEmpty { values.library }, repoToken = account.libraryId.isNotEmpty())
        }
    }
}

@Composable
internal fun rememberStorageFormState(
    protocol: String,
    entry: NetworkEntry? = null,
    snackbar: SnackbarHostState,
    onSave: suspend (Network) -> Unit,
): StorageFormState = key(protocol, entry?.id) {
    val old = entry?.network
    val form = rememberSaveable(stateSaver = storageFormSaver) {
        val cloud = old?.extras?.cloud ?: CloudDriveExtras()
        val nfs = old?.extras?.nfs ?: NfsDriveExtras()
        mutableStateOf(StorageFormValues(
            name = old?.name.orEmpty(),
            server = old?.host.orEmpty(), username = old?.username.orEmpty(),
            root = cloud.rootId, library = cloud.libraryId,
            account = cloud.accountId, connection = cloud.connectionId.ifBlank { Uuid.random().toString() },
            nfsVersion = nfs.version, export = nfs.exportPath, uid = nfs.uid.toString(), gid = nfs.gid.toString(),
            groups = nfs.groups.joinToString(","), mountPort = nfs.mountPort.toString(),
            repoToken = cloud.seafileRepoToken, readOnly = cloud.readOnly,
        ))
    }
    val password = remember { mutableStateOf(old?.password.orEmpty()) }
    val scope = rememberCoroutineScope()
    val currentOnSave = rememberUpdatedState(onSave)
    val state = remember {
        StorageFormState(protocol, form, password, old?.pinned ?: false, scope, snackbar) { network ->
            currentOnSave.value(network)
        }
    }
    state.content = rememberStorageContentFormState(protocol,
        runCatching { state.values.network(protocol, state.password, old?.pinned ?: false) }.getOrNull(), old)
    val runtimeSession = WebUiAttachment.target?.httpUrl("/").orEmpty() + "|" + WebUiAttachment.sessionToken
    LaunchedEffect(runtimeSession) {
        state.authorization = null
        state.accounts = emptyList()
        if (state.kind != null) try { state.accounts = StorageAccountPlatform.accounts() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; state.error = failure.message }
    }
    LaunchedEffect(state.values.account, runtimeSession) {
        state.libraries = emptyList()
        if (protocol == "Seafile" && state.values.account.isNotEmpty()) try {
            state.libraries = StorageAccountPlatform.libraries(state.values.account)
            if (state.values.library.isEmpty() && state.libraries.size == 1) {
                state.values = state.values.copy(library = state.libraries.single().id, readOnly = state.libraries.single().readOnly)
            }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; state.error = failure.message }
    }
    LaunchedEffect(state.authorization?.handle, runtimeSession) {
        val started = state.authorization ?: return@LaunchedEffect
        try {
            while (true) {
                delay(1000)
                val result = StorageAccountPlatform.poll(started.handle)
                if (result.status == "authorized") {
                    val account = checkNotNull(result.account)
                    state.accounts = StorageAccountPlatform.accounts()
                    state.values = state.values.copy(account = account.id, repoToken = account.libraryId.isNotEmpty(),
                        library = account.libraryId.ifEmpty { state.values.library }, readOnly = account.readOnly)
                    state.authorization = null
                    break
                }
                if (result.status in setOf("denied", "failed", "expired", "cancelled")) {
                    state.error = AppStrings.storage_authorization_stopped
                    state.authorization = null
                    break
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { state.error = failure.message; state.authorization = null }
        finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(3500) { runCatching { StorageAccountPlatform.cancel(started.handle) } }
            }
        }
    }
    state
}

internal fun LazyGridScope.NetworkStorageForm(
    protocol: String, values: StorageFormValues, onChange: (StorageFormValues) -> Unit,
    password: String, onPassword: (String) -> Unit, token: String, onToken: (String) -> Unit,
    accounts: List<StorageAccount>, libraries: List<StorageLibrary>, authorization: StorageAuthorizationStart?,
    busy: Boolean, error: String?, onAuthorize: (Boolean) -> Unit = {},
    onCancel: () -> Unit = {}, onOpen: () -> Unit = {}, onRemove: () -> Unit = {}, onInstallSeafile: () -> Unit = {},
    existingNetwork: Network? = null,
    onAuthorizeSharing: () -> Unit = {},
) {
    val kind = storageKind(protocol)
    val counts = libraries.groupingBy { it.name }.eachCount()
    val libraryLabels = libraries.associate { library ->
        library.id to if (counts.getValue(library.name) > 1) library.name + " (" + library.id + ")" else library.name
    }
    val errors = values.validationErrors(protocol)
    item { NetworkNameField(values.name, { onChange(values.copy(name = it)) }, defaultNetworkName(protocol, values.server)) }
    if (protocol in setOf("NFS", "HttpDirectory", "Nextcloud", "Seafile")) item {
        StorageTextField(values.server, { onChange(values.copy(server = it, account = if (protocol in setOf("Nextcloud", "Seafile")) "" else values.account)); onCancel() },
            AppStrings.ui_link_address, errors["server"],
            if (protocol == "NFS") AppStrings.storage_nfs_server_hint else AppStrings.storage_http_server_hint)
    }
    if (protocol == "HttpDirectory") {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(AppStrings.storage_http_read_only) }
        item { StorageTextField(values.username, { onChange(values.copy(username = it)) }, AppStrings.ui_username, hint = AppStrings.storage_http_credentials_hint) }
        item { PasswordOutlinedTextField(password, onPassword, label = { Text(AppStrings.ui_password) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    }
    if (protocol == "NFS") {
        item { EditableExposedDropdownMenu(StringListUiState(listOf("NFSv3", "NFSv4.0")), if (values.nfsVersion == 3) "NFSv3" else "NFSv4.0",
            { onChange(values.copy(nfsVersion = if (it == "NFSv3") 3 else 4)) }, label = { Text(AppStrings.storage_nfs_version) }, readOnly = true, optionContent = { Text(it) }, modifier = Modifier.fillMaxWidth()) }
        item { StorageTextField(values.export, { onChange(values.copy(export = it)) }, AppStrings.storage_nfs_export, errors["export"], AppStrings.storage_nfs_export_hint) }
        item { StorageTextField(values.uid, { onChange(values.copy(uid = it)) }, "UID", errors["uid"], AppStrings.storage_nfs_identity_hint) }
        item { StorageTextField(values.gid, { onChange(values.copy(gid = it)) }, "GID", errors["gid"], AppStrings.storage_nfs_identity_hint) }
        item { StorageTextField(values.groups, { onChange(values.copy(groups = it)) }, AppStrings.storage_nfs_groups, errors["groups"], AppStrings.storage_nfs_groups_hint) }
        if (values.nfsVersion == 3) item { StorageTextField(values.mountPort, { onChange(values.copy(mountPort = it)) }, AppStrings.storage_nfs_mount_port, errors["mountPort"], AppStrings.storage_nfs_mount_port_hint) }
    }
    if (kind != null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = AppStrings.storage_account,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = AppStrings.storage_account_hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (accounts.isNotEmpty()) errors["account"]?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (accounts.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = AppStrings.storage_no_accounts,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = AppStrings.storage_authorize_first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (protocol != "Seafile") {
                                Button(
                                    onClick = { onAuthorize(false) },
                                    enabled = !busy && authorization == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(AppStrings.storage_authorize)
                                }
                            }
                        }
                    }
                } else {
                    accounts.forEach { account ->
                        val selected = values.account == account.id
                        val statusText = when {
                            account.needsAuthorization -> AppStrings.storage_authorization_required
                            account.readOnly -> AppStrings.ui_read_only
                            else -> AppStrings.storage_connected
                        }
                        val statusContainer = when {
                            account.needsAuthorization -> MaterialTheme.colorScheme.errorContainer
                            account.readOnly -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        }
                        val statusContent = when {
                            account.needsAuthorization -> MaterialTheme.colorScheme.onErrorContainer
                            account.readOnly -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onChange(
                                            values.copy(
                                                account = account.id,
                                                library = account.libraryId,
                                                repoToken = account.libraryId.isNotEmpty(),
                                                readOnly = account.readOnly,
                                            )
                                        )
                                    },
                                ),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = account.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    val secondary = account.username.ifBlank { account.server }
                                    if (secondary.isNotBlank()) {
                                        Text(
                                            text = secondary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Surface(
                                        modifier = Modifier.padding(top = 6.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = statusContainer,
                                    ) {
                                        Text(
                                            text = statusText,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = statusContent,
                                        )
                                    }
                                }
                                RadioButton(selected = selected, onClick = null)
                            }
                        }
                    }
                }

                if (authorization != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = AppStrings.storage_authorization_waiting,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (authorization.userCode.isNotEmpty()) {
                                Text(
                                    text = AppStrings.storage_confirm_code + ": " + authorization.userCode,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onOpen) {
                                    Icon(Icons.AutoMirrored.Default.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(AppStrings.storage_open_authorization)
                                }
                                OutlinedButton(onClick = onCancel) {
                                    Text(AppStrings.ui_cancel)
                                }
                            }
                        }
                    }
                } else if (accounts.isNotEmpty() && protocol != "Seafile") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onAuthorize(values.account.isNotEmpty()) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (values.account.isNotEmpty()) Icons.Default.Refresh else Icons.Default.Add,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (values.account.isNotEmpty()) {
                                    AppStrings.storage_reauthorize
                                } else {
                                    AppStrings.storage_authorize
                                }
                            )
                        }
                        if (values.account.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onRemove,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(AppStrings.storage_remove_authorization)
                            }
                        }
                    }
                }

                if (protocol in setOf("GoogleDrive", "Dropbox") && values.account.isNotEmpty() && authorization == null) {
                    Text(
                        text = AppStrings.storage_sharing_authorization_hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onAuthorizeSharing,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(AppStrings.storage_authorize_sharing) }
                }

                if (values.readOnly) {
                    Text(
                        text = AppStrings.ui_read_only,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
    if (protocol == "Seafile") {
            item { PasswordOutlinedTextField(token, onToken, label = { Text(AppStrings.storage_seafile_token) },
                supportingText = { Text(AppStrings.storage_seafile_token_hint) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Row(Modifier.fillMaxWidth().toggleable(values.repoToken, role = Role.Checkbox, onValueChange = { onChange(values.copy(repoToken = it)) }), verticalAlignment = Alignment.CenterVertically) { Checkbox(values.repoToken, null); Text(AppStrings.storage_seafile_repo_token) } }
            item { Button(onClick = onInstallSeafile, enabled = !busy && token.isNotBlank()) { Text(AppStrings.storage_authorize) } }
            item {
                if (libraries.isNotEmpty()) EditableExposedDropdownMenu(StringListUiState(libraries.map { libraryLabels.getValue(it.id) }), libraryLabels[values.library].orEmpty(),
                    { name -> libraries.firstOrNull { libraryLabels[it.id] == name }?.let { onChange(values.copy(library = it.id, readOnly = it.readOnly)) } },
                    label = { Text(AppStrings.storage_library) }, isError = errors["library"] != null,
                    supportingText = { Text(errors["library"] ?: AppStrings.storage_library_hint) },
                    readOnly = true, optionContent = { Text(it) }, modifier = Modifier.fillMaxWidth())
                else StorageTextField(values.library, { onChange(values.copy(library = it)) }, AppStrings.storage_library_id, errors["library"], AppStrings.storage_library_hint)
            }
        }
        if (protocol in setOf("GoogleDrive", "Dropbox")) item {
            OutlinedTextField(values.root, { onChange(values.copy(root = it)) }, label = { Text(AppStrings.storage_root_id) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (protocol in setOf("Nextcloud", "Seafile")) item {
            OutlinedTextField(values.root, { onChange(values.copy(root = it)) }, label = { Text(AppStrings.storage_root_path) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (values.readOnly) item(span = { GridItemSpan(maxLineSpan) }) { Text(AppStrings.ui_read_only) }
    if (busy) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    error?.let { message ->
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StorageTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    error: String? = null, hint: String? = null,
) {
    OutlinedTextField(
        value, onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: hint)?.let { message -> { Text(message) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
