package com.folderspan.ui.screen.network.form

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.extensions.randomString
import com.folderspan.service.network.*
import com.folderspan.service.webui.WebUiAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import strings.AppStrings

internal val contentEncryptionProtocols = setOf("FTP", "SFTP", "SMB", "NFS", "WebDav", "S3",
    "GoogleDrive", "Dropbox", "Nextcloud", "Seafile")

internal fun storageContentRuntimeKey(): String =
    WebUiAttachment.target?.httpUrl("/").orEmpty() + "|" + WebUiAttachment.sessionToken + "|" + WebUiAttachment.sessionGeneration

private fun NetworkConnectionConfig.withoutContentReference() =
    copy(extras = extras.copy(contentEncryption = null))

private fun NetworkConnectionConfig.remoteTarget() =
    copy(extras = extras.copy(contentEncryption = null, cloud = extras.cloud.copy(connectionId = "")))

internal fun storageContentConfigForForm(network: Network?, existing: Network? = null, connectionId: String = ""): NetworkConnectionConfig? {
    val config = network?.connectionConfig()?.takeIf { it.protocol in contentEncryptionProtocols } ?: return null
    val old = existing?.connectionConfig()
    return config.copy(extras = config.extras.copy(
        cloud = config.extras.cloud.copy(connectionId = config.extras.cloud.connectionId.ifEmpty {
            old?.extras?.cloud?.connectionId.orEmpty().ifEmpty { connectionId }
        }),
        contentEncryption = old?.extras?.contentEncryption?.takeIf {
            old.remoteTarget() == config.remoteTarget()
        }))
}

private fun Network.withConnectionId(connectionId: String): Network =
    Network(name, pathSeparator, protocol, host, username, password, pinned,
        extras.copy(cloud = extras.cloud.copy(connectionId = connectionId)))

private fun Network.withContentReference(reference: StorageContentLocationReference?): Network =
    Network(name, pathSeparator, protocol, host, username, password, pinned,
        extras.copy(contentEncryption = reference))

internal fun storageContentFailureMessage(failure: Throwable): String = when ((failure as? StorageContentException)?.code) {
    StorageContentErrorCode.Unavailable -> AppStrings.storage_content_unavailable
    StorageContentErrorCode.Locked -> AppStrings.storage_content_password_required
    StorageContentErrorCode.UnlockFailed -> AppStrings.storage_content_password_unlock_failed
    StorageContentErrorCode.UnsupportedFormat -> AppStrings.storage_content_format_unsupported
    StorageContentErrorCode.DamagedLocation -> AppStrings.storage_content_location_damaged
    StorageContentErrorCode.InitializationConflict -> AppStrings.storage_content_initialization_conflict
    StorageContentErrorCode.ReadOnly -> AppStrings.ui_read_only
    StorageContentErrorCode.UncertainResult -> AppStrings.storage_content_result_uncertain
    StorageContentErrorCode.Conflict -> AppStrings.storage_content_location_conflict
    StorageContentErrorCode.SourceChanged -> AppStrings.storage_content_target_changed
    null -> AppStrings.storage_content_action_failed
}

@Stable
internal class StorageContentFormState(
    val protocol: String,
    config: NetworkConnectionConfig?,
    private val enabledState: MutableState<Boolean>,
    private val operations: StorageContentOperations = StorageContentPlatform,
    private val runtime: String = storageContentRuntimeKey(),
    private val onInvalidate: () -> Unit = {},
) {
    var config by mutableStateOf(config)
        private set
    var enabled: Boolean
        get() = enabledState.value
        private set(value) { enabledState.value = value }
    var location by mutableStateOf<StorageContentLocationStatus?>(null)
        private set
    var reference by mutableStateOf(config?.extras?.contentEncryption)
        private set
    var status by mutableStateOf<StorageContentStatus?>(null)
        private set
    var password by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var validationMessage by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var inspecting by mutableStateOf(false)
        private set
    private var inspectionFailure: Throwable? = null
    private var active = true
    private var saved = false
    private var saveJob: Job? = null
    private var pendingLock: NetworkConnectionConfig? = null

    val supportedProtocol get() = protocol in contentEncryptionProtocols
    val existingLocation get() = !reference?.vaultId.isNullOrEmpty()
    private val locationReady get() = location?.available == true && location?.writable == true
    private val offline get() = location == null && inspectionFailure !is StorageContentException
    val canConfigure get() = supportedProtocol && (locationReady || offline)
    val passwordReady get() = password.isNotEmpty()

    fun updateConfig(value: NetworkConnectionConfig?) {
        if (config == value) return
        saveJob?.cancel()
        config = value
        location = null
        reference = value?.extras?.contentEncryption
        status = null
        inspectionFailure = null
        inspecting = false
        message = null
        saved = false
    }

    private fun current(target: NetworkConnectionConfig? = config) {
        if (!active || runtime != storageContentRuntimeKey()) {
            invalidate(notify = false)
            throw CancellationException(AppStrings.storage_content_target_changed)
        }
        if (target != config) throw CancellationException(AppStrings.storage_content_target_changed)
    }

    private fun configured(reference: StorageContentLocationReference, target: NetworkConnectionConfig = checkNotNull(config)) =
        target.copy(extras = target.extras.copy(contentEncryption = reference))

    suspend fun inspect() {
        val target = config ?: return
        if (!supportedProtocol || inspecting) return
        current(target)
        inspecting = true
        inspectionFailure = null
        message = null
        try {
            val result = operations.inspectPasswordLocation(target).getOrThrow()
            current(target)
            val inspectedReference = result.reference ?: reference
            val inspectedStatus = inspectedReference?.takeIf { it.vaultId.isNotEmpty() }
                ?.let { operations.status(configured(it, target)).getOrThrow() }
            current(target)
            location = result
            reference = inspectedReference
            status = inspectedStatus
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            if (active && config == target) {
                location = null
                status = null
                inspectionFailure = failure
                message = storageContentFailureMessage(failure).takeIf { failure is StorageContentException }
            }
        }
        finally { if (config == target) inspecting = false }
    }

    fun changeEnabled(value: Boolean) {
        if (!active || busy) return
        if (value && !canConfigure) return
        enabled = value
        if (!value) clearPasswords()
        validationMessage = null
    }

    fun editPassword(value: String) { if (!busy && active) { password = value; validationMessage = null } }

    fun generatePassword() {
        if (!busy && active) try { editPassword(1024.randomString()) }
        catch (failure: Exception) { validationMessage = AppStrings.storage_content_password_generation_failed }
    }

    private fun validatePassword() {
        if (!enabled || password.isEmpty() && existingLocation && status?.unlocked == true) return
        validationMessage = when {
            password.isEmpty() -> AppStrings.storage_content_password_required
            password.length > 1024 || !existingLocation && password.length < 12 -> AppStrings.storage_content_password_length
            else -> null
        }
        validationMessage?.let { error(it) }
    }

    suspend fun save(network: Network, persist: suspend (Network) -> Unit) {
        current()
        val target = checkNotNull(config)
        validatePassword()
        val connection = if (network.extras.cloud.connectionId.isEmpty())
            network.withConnectionId(target.extras.cloud.connectionId) else network
        check(connection.connectionConfig().withoutContentReference() == target.withoutContentReference()) {
            AppStrings.storage_content_target_changed
        }
        saveJob = currentCoroutineContext().job
        busy = true
        message = null
        try {
            if (enabled && location == null && !inspecting) inspect()
            current(target)
            validatePassword()
            var selected = reference?.takeIf { it.vaultId.isNotEmpty() }
            if (enabled && !locationReady) {
                check(offline && canConfigure) { message ?: AppStrings.storage_content_unavailable }
                selected = selected ?: StorageContentLocationReference()
            } else if (enabled) {
                if (password.isNotEmpty()) {
                    val pass = password.toCharArray()
                    try {
                        val root = selected?.root ?: checkNotNull(location).root
                        val candidate = selected ?: StorageContentLocationReference(root = root)
                        selected = if (selected == null) operations.setupPassword(configured(candidate, target), pass).getOrThrow()
                        else selected.also { operations.unlockPassword(configured(it, target), pass).getOrThrow() }
                        current(target)
                        reference = selected
                        val updatedStatus = operations.status(configured(checkNotNull(selected), target)).getOrThrow()
                        current(target)
                        status = updatedStatus
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) {
                        message = storageContentFailureMessage(failure)
                        error(checkNotNull(message))
                    } finally {
                        pass.fill('\u0000')
                        if ((!active || config != target) && selected != null && runtime == storageContentRuntimeKey()) withContext(NonCancellable) {
                            withTimeoutOrNull(3500) { operations.lock(configured(selected, target)) }
                        }
                    }
                } else {
                    check(selected != null && status?.unlocked == true) { AppStrings.storage_content_password_required }
                    try { operations.setEnabled(configured(checkNotNull(selected).copy(enabled = true), target), true).getOrThrow() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) {
                        message = storageContentFailureMessage(failure)
                        error(checkNotNull(message))
                    }
                }
            }
            current(target)
            val selectedReference = selected?.copy(enabled = enabled)
            val persistedConnection = if (selectedReference == null) network else connection
            persist(persistedConnection.withContentReference(selectedReference))
            current(target)
            saved = true
        } finally {
            if (config == target) clearPasswords()
            saveJob = null
            busy = false
        }
    }

    fun invalidate() = invalidate(notify = true)

    private fun invalidate(notify: Boolean) {
        if (!active) return
        pendingLock = if (!saved) reference?.takeIf { it.vaultId.isNotEmpty() }?.let(::configured) else null
        active = false
        saveJob?.cancel()
        status = null
        clearPasswords()
        if (notify) onInvalidate()
    }

    fun detach(): NetworkConnectionConfig? {
        invalidate(notify = false)
        return pendingLock
    }

    private fun clearPasswords() {
        password = ""
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun rememberStorageContentFormState(
    protocol: String,
    network: Network?,
    existing: Network? = null,
    operations: StorageContentOperations = StorageContentPlatform,
): StorageContentFormState {
    val localConnectionId = rememberSaveable(protocol, existing?.extras?.cloud?.connectionId) { Uuid.random().toString() }
    val config = storageContentConfigForForm(network, existing, localConnectionId)
    val runtime = storageContentRuntimeKey()
    var revision by remember(protocol) { mutableIntStateOf(0) }
    val enabled = remember(protocol, existing, runtime, revision) { mutableStateOf(existing?.extras?.contentEncryption?.enabled == true) }
    val state = remember(protocol, existing, runtime, operations, revision) {
        StorageContentFormState(protocol, config, enabled, operations, runtime) { revision++ }
    }
    SideEffect { state.updateConfig(config) }
    LaunchedEffect(state, config) { state.inspect() }
    DisposableEffect(state) {
        onDispose {
            val previous = state.detach()
            if (previous != null && runtime == storageContentRuntimeKey()) CoroutineScope(Dispatchers.Default).launch {
                if (runtime == storageContentRuntimeKey()) withTimeoutOrNull(3500) { operations.lock(previous) }
            }
        }
    }
    return state
}
