package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.extensions.formatBytes
import com.folderspan.service.network.*
import com.folderspan.ui.components.pagestate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import strings.AppStrings
import kotlin.time.Instant

enum class StorageDetailsMode { Versions, Sharing }

data class StorageDetailsDialogUiState(
    val fileName: String,
    val mode: StorageDetailsMode,
    val serviceValidationPending: Boolean,
    val capabilities: StorageAdvancedCapabilities = StorageAdvancedCapabilities(),
    val versions: List<StorageVersion> = emptyList(),
    val shares: List<StorageShare> = emptyList(),
    val pageState: PageViewState = PageViewState.Content,
    val nextCursor: String? = null,
    val busy: Boolean = false,
    val googleDrive: Boolean = false,
) {
    fun allows(feature: StorageAdvancedFeature): Boolean =
        !serviceValidationPending && !busy && capabilities.availability(feature) == StorageFeatureAvailability.AVAILABLE
}

internal fun storageShareFormRequest(
    capabilities: StorageAdvancedCapabilities,
    kind: StorageShareKind,
    role: String,
    recipient: String,
    expiry: String,
): Result<StorageShareRequest> = runCatching {
    val expiresAt = expiry.trim().takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilliseconds() }
    StorageShareRequest(kind, role, recipient.trim().takeIf { kind == StorageShareKind.INVITATION }, expiresAt)
        .also(capabilities::validateShare)
}

// Per-backend real-service acceptance evidence is recorded in verification.md.
internal fun storageServiceValidationPending(protocol: String): Boolean =
    protocol !in setOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile")

@Composable
fun StorageDetailsDialogContainer(
    file: FileSimpleInfo,
    network: Network,
    mode: StorageDetailsMode,
    onDismiss: () -> Unit,
) {
    // Each backend leaves this gate after its real-service advanced-operation checks pass.
    // No application setting can bypass the acceptance gate.
    var state by remember(file, network, mode) {
        mutableStateOf(StorageDetailsDialogUiState(file.name, mode, serviceValidationPending = storageServiceValidationPending(network.protocol),
            googleDrive = network.protocol == "GoogleDrive"))
    }
    val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean = false) {
        if (state.serviceValidationPending) return
        state = state.copy(busy = true, pageState = if (append) state.pageState else PageViewState.Loading)
        try {
            val caps = network.storageAdvancedCapabilities(file.storagePath).getOrThrow()
            val cursor = if (append) state.nextCursor else null
            state = when (mode) {
                StorageDetailsMode.Versions -> {
                    caps.require(StorageAdvancedFeature.LIST_VERSIONS)
                    val page = network.storageVersions(file.storagePath, cursor).getOrThrow()
                    state.copy(capabilities = caps, versions = (if (append) state.versions else emptyList()) + page.entries,
                        nextCursor = page.nextCursor, busy = false, pageState = PageViewState.Content)
                }
                StorageDetailsMode.Sharing -> {
                    caps.require(StorageAdvancedFeature.LIST_SHARES)
                    val page = network.storageShares(file.storagePath, cursor).getOrThrow()
                    state.copy(capabilities = caps, shares = (if (append) state.shares else emptyList()) + page.entries,
                        nextCursor = page.nextCursor, busy = false, pageState = PageViewState.Content)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Do not render raw remote errors: share URLs and recipient details can occur in them.
            val message = if (error is StorageFeatureException) storageAvailabilityText(error.availability)
                else AppStrings.storage_operation_failed
            state = state.copy(busy = false, pageState = PageViewState.Error(PageErrorState(PageErrorType.General, message)))
        }
    }
    fun mutate(block: suspend () -> Unit) {
        if (state.serviceValidationPending || state.busy) return
        scope.launch {
            state = state.copy(busy = true)
            try {
                block()
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                state = state.copy(busy = false, pageState = PageViewState.Error(PageErrorState(
                    PageErrorType.General, if (error is StorageFeatureException) storageAvailabilityText(error.availability)
                    else AppStrings.storage_operation_failed)))
            }
        }
    }
    LaunchedEffect(file, network, mode) { load() }
    StorageDetailsDialog(
        state = state,
        onDismiss = onDismiss,
        onRetry = { scope.launch { load() } },
        onLoadMore = { scope.launch { load(append = true) } },
        onRestore = { version -> mutate { network.restoreStorageVersion(version, confirmed = true).getOrThrow() } },
        onCreateShare = { request -> mutate { network.createStorageShare(file.storagePath, request, confirmed = true).getOrThrow() } },
        onRevokeShare = { share -> mutate { network.revokeStorageShare(share).getOrThrow() } },
    )
}

private fun storageAvailabilityText(availability: StorageFeatureAvailability): String = when (availability) {
    StorageFeatureAvailability.AVAILABLE -> AppStrings.storage_available
    StorageFeatureAvailability.PERMISSION_DENIED -> AppStrings.storage_permission_denied
    StorageFeatureAvailability.AUTHORIZATION_REQUIRED -> AppStrings.storage_reauthorize_required
    StorageFeatureAvailability.UNSUPPORTED -> AppStrings.storage_unsupported
}

private fun storageRoleText(role: String): String = when (role) {
    "reader", "viewer" -> AppStrings.storage_role_read
    "writer", "editor" -> AppStrings.storage_role_write
    "commenter" -> AppStrings.storage_role_comment
    "owner" -> AppStrings.storage_role_owner
    "reader+reshare" -> AppStrings.storage_role_read_reshare
    "writer+reshare" -> AppStrings.storage_role_write_reshare
    else -> role
}

@Composable
fun StorageDetailsDialog(
    state: StorageDetailsDialogUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRestore: (StorageVersion) -> Unit = {},
    onCreateShare: (StorageShareRequest) -> Unit = {},
    onRevokeShare: (StorageShare) -> Unit = {},
) {
    var restore by remember(state.fileName, state.mode) { mutableStateOf<StorageVersion?>(null) }
    var revoke by remember(state.fileName, state.mode) { mutableStateOf<StorageShare?>(null) }
    var create by remember(state.fileName, state.mode) { mutableStateOf<StorageShareRequest?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.mode == StorageDetailsMode.Versions) AppStrings.storage_versions else AppStrings.storage_sharing) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(AppStrings.ui_cancel) } },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.fileName, style = MaterialTheme.typography.titleSmall)
                if (state.serviceValidationPending) {
                    Text(AppStrings.storage_service_validation_pending, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PageStateLayout(state.pageState, fillMaxSize = false, onRetry = onRetry) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.mode == StorageDetailsMode.Versions) {
                            if (!state.serviceValidationPending && state.versions.isEmpty()) Text(AppStrings.storage_no_versions)
                            state.versions.forEach { version ->
                                key(version.id) {
                                    Column {
                                        Text(version.id, maxLines = 2)
                                        Text(listOfNotNull(version.size?.formatBytes(),
                                            version.updatedDate?.let { Instant.fromEpochMilliseconds(it).toString() }).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall)
                                        TextButton(enabled = state.allows(StorageAdvancedFeature.RESTORE_VERSION),
                                            onClick = { restore = version }) { Text(AppStrings.storage_restore_version) }
                                    }
                                }
                            }
                            if (!state.allows(StorageAdvancedFeature.RESTORE_VERSION)) {
                                Text(if (state.serviceValidationPending) AppStrings.storage_actions_pending_validation else
                                    storageAvailabilityText(state.capabilities.availability(StorageAdvancedFeature.RESTORE_VERSION)),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            if (!state.serviceValidationPending && state.shares.isEmpty()) Text(AppStrings.storage_no_shares)
                            state.shares.forEach { share ->
                                key(share.kind, share.id) {
                                    Column {
                                        Text(share.recipient ?: AppStrings.storage_link_share)
                                        Text(storageRoleText(share.role), style = MaterialTheme.typography.bodySmall)
                                        share.expiresAt?.let { Text(Instant.fromEpochMilliseconds(it).toString(), style = MaterialTheme.typography.bodySmall) }
                                        share.url?.let { url -> SelectionContainer { Text(url, maxLines = 3) } }
                                        TextButton(enabled = share.canRevoke && state.allows(StorageAdvancedFeature.REVOKE_SHARE),
                                            onClick = { revoke = share }) { Text(AppStrings.storage_revoke_share) }
                                    }
                                }
                            }
                            StorageShareForm(state, onCreate = { create = it })
                        }
                        if (state.nextCursor != null) TextButton(onClick = onLoadMore, enabled = !state.busy) { Text(AppStrings.storage_load_more) }
                    }
                }
            }
        },
    )
    val pendingRestore = restore
    val pendingRevoke = revoke
    val pendingCreate = create
    if (pendingRestore != null || pendingRevoke != null || pendingCreate != null) {
        val feature = when {
            pendingRestore != null -> StorageAdvancedFeature.RESTORE_VERSION
            pendingRevoke != null -> StorageAdvancedFeature.REVOKE_SHARE
            pendingCreate?.kind == StorageShareKind.INVITATION -> StorageAdvancedFeature.INVITE_USER
            else -> StorageAdvancedFeature.CREATE_LINK
        }
        val dismiss = { restore = null; revoke = null; create = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(AppStrings.storage_confirm_action) },
            text = { Text(when {
                pendingRestore != null -> AppStrings.storage_restore_confirmation +
                    if (state.googleDrive) "\n" + AppStrings.storage_google_restore_retention else ""
                pendingRevoke != null -> AppStrings.storage_revoke_confirmation
                else -> AppStrings.storage_share_confirmation
            }) },
            dismissButton = { TextButton(onClick = dismiss) { Text(AppStrings.ui_cancel) } },
            confirmButton = {
                TextButton(enabled = state.allows(feature), onClick = {
                    when {
                        pendingRestore != null -> onRestore(pendingRestore)
                        pendingRevoke != null -> onRevokeShare(pendingRevoke)
                        pendingCreate != null -> onCreateShare(pendingCreate)
                    }
                    dismiss()
                }) { Text(AppStrings.storage_confirm_action) }
            },
        )
    }
}

@Composable
private fun StorageShareForm(state: StorageDetailsDialogUiState, onCreate: (StorageShareRequest) -> Unit) {
    var kind by remember(state.fileName) { mutableStateOf(StorageShareKind.LINK) }
    var role by remember(state.fileName, state.capabilities.shareRoles) { mutableStateOf(state.capabilities.shareRoles.sorted().firstOrNull().orEmpty()) }
    var recipient by remember(state.fileName) { mutableStateOf("") }
    var expiry by remember(state.fileName) { mutableStateOf("") }
    val feature = if (kind == StorageShareKind.LINK) StorageAdvancedFeature.CREATE_LINK else StorageAdvancedFeature.INVITE_USER
    val request = storageShareFormRequest(state.capabilities, kind, role, recipient, expiry).getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.storage_create_share, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == StorageShareKind.LINK, onClick = { kind = StorageShareKind.LINK }, label = { Text(AppStrings.storage_link_share) })
            FilterChip(selected = kind == StorageShareKind.INVITATION, onClick = { kind = StorageShareKind.INVITATION }, label = { Text(AppStrings.storage_invite_user) })
        }
        if (kind == StorageShareKind.INVITATION) OutlinedTextField(value = recipient, onValueChange = { recipient = it },
            label = { Text(AppStrings.storage_share_recipient) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = state.allows(feature))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.capabilities.shareRoles.sorted().forEach { value ->
                FilterChip(selected = role == value, onClick = { role = value }, label = { Text(storageRoleText(value)) }, enabled = state.allows(feature))
            }
        }
        if (state.capabilities.shareExpiry) OutlinedTextField(value = expiry, onValueChange = { expiry = it },
            label = { Text(AppStrings.storage_share_expiry) }, placeholder = { Text("2027-01-01T00:00:00Z") },
            supportingText = { Text(AppStrings.storage_share_expiry_hint) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = state.allows(feature),
            isError = expiry.isNotBlank() && runCatching { Instant.parse(expiry.trim()) }.isFailure)
        TextButton(enabled = state.allows(feature) && request != null, onClick = { request?.let(onCreate) }) { Text(AppStrings.storage_create_share) }
        if (!state.allows(feature)) Text(if (state.serviceValidationPending) AppStrings.storage_actions_pending_validation else
            storageAvailabilityText(state.capabilities.availability(feature)), style = MaterialTheme.typography.bodySmall)
    }
}
