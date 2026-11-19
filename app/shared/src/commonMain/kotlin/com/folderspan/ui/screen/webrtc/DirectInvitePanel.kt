package com.folderspan.ui.screen.webrtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.writeClipboardText
import com.folderspan.data.main.webrtc.WebRtcDirectConnectionException
import com.folderspan.data.main.webrtc.WebRtcDirectFailure
import com.folderspan.data.main.webrtc.WebRtcDirectInvite
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.data.main.webrtc.WebRtcShareCodeError
import com.folderspan.data.main.webrtc.WebRtcShareCodeException
import com.folderspan.getSocketDevice
import com.folderspan.localization.localizedName
import com.folderspan.service.webrtc.models.toRoomDisplayLabel
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.ui.state.main.DeviceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings
import kotlin.time.Clock

private data class InviteDevicePreview(
    val name: String,
    val decision: CompletableDeferred<Boolean>,
)

@Composable
internal fun DirectInvitePanel(deviceState: DeviceState, onOpenFiles: () -> Unit) {
    val runtimeError = deviceState.directInviteAvailabilityError()
    if (runtimeError != null) {
        Text(runtimeError, modifier = Modifier.padding(16.dp))
        return
    }
    val roles = koinInject<DeviceRoleState>().roles
    val scope = rememberCoroutineScope()
    val states by deviceState.directInviteStates.collectAsState()
    var signalingUrl by remember { mutableStateOf(deviceState.directInviteSignalingUrl()) }
    var devicePreview by remember { mutableStateOf<InviteDevicePreview?>(null) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(runCatching { getSocketDevice().httpsPort.toString() }.getOrDefault("")) }
    var stun by remember { mutableStateOf("") }
    var turnUrl by remember { mutableStateOf("") }
    var turnUsername by remember { mutableStateOf("") }
    var turnPassword by remember { mutableStateOf("") }
    var roleId by remember { mutableStateOf<Long?>(null) }
    var choosingRole by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf<WebRtcDirectInvite?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0L) }
    val role = roles.firstOrNull { it.id == roleId }
    val canUseDeviceEntry = host.isBlank() || port.toIntOrNull() in 1..65535
    val currentInvite = invitation
    val inviteText = remember(currentInvite) { currentInvite?.let(WebRtcShareCode::displayDeviceInvite) }

    LaunchedEffect(currentInvite) {
        while (currentInvite != null) {
            seconds = ((currentInvite.expiresAtEpochMillis - Clock.System.now().toEpochMilliseconds() + 999) / 1_000)
                .coerceAtLeast(0)
            if (seconds == 0L) break
            delay(1_000)
        }
    }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            working = true
            feedback = ""
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback = directInviteError(error)
            } finally {
                working = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = signalingUrl,
            onValueChange = { signalingUrl = it.take(512); feedback = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppStrings.direct_invite_signaling_url) },
            enabled = !working,
            singleLine = true,
        )
        val validServer = WebRtcShareCode.isSecureSignalingUrl(signalingUrl)
        if (!validServer) Text(AppStrings.direct_invite_signaling_invalid)
        DirectInviteEntry(codeInput, working || !validServer, onChange = { codeInput = it; feedback = "" }) {
            runAction {
                deviceState.setDirectInviteSignalingUrl(signalingUrl)
                deviceState.connectDirectInvite(codeInput) { name ->
                    val preview = InviteDevicePreview(name, CompletableDeferred())
                    devicePreview = preview
                    try {
                        if (!preview.decision.await()) throw CancellationException("device connection cancelled")
                    } finally {
                        devicePreview = null
                    }
                }
                codeInput = ""
            }
        }
        devicePreview?.let { preview ->
            ListItem(
                headlineContent = { Text(preview.name) },
                trailingContent = {
                    Column {
                        Button(onClick = { preview.decision.complete(true) }) { Text(AppStrings.ui_connect) }
                        TextButton(onClick = { preview.decision.complete(false) }) { Text(AppStrings.ui_cancel) }
                    }
                },
            )
        }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodyMedium)

        for ((attemptId, status) in states) {
            ListItem(
                headlineContent = { Text(deviceState.directInvitePeerName(attemptId)) },
                supportingContent = { Text(status.toRoomDisplayLabel()) },
                trailingContent = {
                    Column {
                        if (deviceState.canOpenDirectInviteDevice(attemptId)) {
                            TextButton(onClick = {
                                if (deviceState.openDirectInviteDevice(attemptId)) onOpenFiles()
                            }) { Text(AppStrings.direct_invite_open_files) }
                        }
                        TextButton(onClick = { scope.launch { deviceState.disconnectDirectInvite(attemptId) } }) {
                            Text(AppStrings.direct_invite_disconnect)
                        }
                    }
                },
            )
        }

        HorizontalDivider()
        Text(AppStrings.direct_invite_create, style = MaterialTheme.typography.titleMedium)
        Text(AppStrings.direct_invite_reachable)
        OutlinedTextField(
            value = host, onValueChange = { host = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(AppStrings.direct_invite_host) }, singleLine = true,
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.take(5) }, modifier = Modifier.fillMaxWidth(),
            label = { Text(AppStrings.direct_invite_port) }, singleLine = true, enabled = host.isNotBlank(),
        )
        WebRtcIceTurnFields(
            stunUrl = stun, onStunUrlChange = { stun = it },
            turnUrl = turnUrl, onTurnUrlChange = { turnUrl = it },
            turnUsername = turnUsername, onTurnUsernameChange = { turnUsername = it },
            turnPassword = turnPassword, onTurnPasswordChange = { turnPassword = it },
        )
        if (turnUrl.isNotBlank()) {
            Text(AppStrings.direct_invite_turn_warning, style = MaterialTheme.typography.bodyMedium)
        }
        Box {
            OutlinedButton(onClick = { choosingRole = true }) { Text(role?.localizedName ?: AppStrings.direct_invite_access) }
            DropdownMenu(expanded = choosingRole, onDismissRequest = { choosingRole = false }) {
                for (option in roles) {
                    DropdownMenuItem(text = { Text(option.localizedName) }, onClick = { roleId = option.id; choosingRole = false })
                }
            }
        }
        Button(enabled = !working && validServer && role != null && canUseDeviceEntry, onClick = {
            runAction {
                deviceState.setDirectInviteSignalingUrl(signalingUrl)
                currentInvite?.let { deviceState.revokeDirectInvite(it) }
                invitation = deviceState.issueDirectInvite(
                    host = host.trim().removeSurrounding("[", "]"),
                    port = if (host.isBlank()) 0 else port.toInt(),
                    roleId = checkNotNull(role).id,
                    stunUrl = stun.trim(),
                    turnUrl = turnUrl.trim(),
                    turnUsername = turnUsername,
                    turnPassword = turnPassword,
                )
            }
        }) { Text(AppStrings.direct_invite_create) }
        if (currentInvite != null && inviteText != null) {
            Text(AppStrings.direct_invite_valid_seconds.format(arg0 = seconds.toString()))
            OutlinedTextField(
                value = inviteText, onValueChange = {}, readOnly = true, maxLines = 3,
                modifier = Modifier.fillMaxWidth(), label = { Text(AppStrings.direct_invite_code) },
            )
            TextButton(enabled = seconds > 0 && !working, onClick = {
                runAction { feedback = if (writeClipboardText(inviteText)) AppStrings.ui_copied else AppStrings.ui_copy_failed }
            }) { Text(AppStrings.direct_invite_copy) }
            TextButton(enabled = !working, onClick = {
                runAction { deviceState.revokeDirectInvite(currentInvite); invitation = null }
            }) { Text(AppStrings.direct_invite_revoke) }
        }
    }
}

@Composable
internal fun DirectInviteEntry(code: String, working: Boolean, onChange: (String) -> Unit, onConnect: () -> Unit) {
    OutlinedTextField(
        value = code, onValueChange = { onChange(it.take(8193)) },
        modifier = Modifier.fillMaxWidth(), label = { Text(AppStrings.direct_invite_code) }, maxLines = 4,
    )
    Button(enabled = code.isNotBlank() && !working, onClick = onConnect) { Text(AppStrings.direct_invite_join) }
}

internal fun directInviteError(error: Exception): String = when (error) {
    is WebRtcShareCodeException -> when (error.reason) {
        WebRtcShareCodeError.WrongKind -> AppStrings.direct_invite_wrong_kind
        WebRtcShareCodeError.Expired -> AppStrings.direct_invite_expired
        WebRtcShareCodeError.UnsupportedVersion -> AppStrings.direct_invite_version
        else -> AppStrings.direct_invite_invalid
    }
    is WebRtcDirectConnectionException -> when (error.failure) {
        WebRtcDirectFailure.Contact -> AppStrings.direct_invite_contact_failed
        WebRtcDirectFailure.Handshake -> AppStrings.direct_invite_handshake_failed
        WebRtcDirectFailure.Ice -> AppStrings.direct_invite_ice_failed
    }
    else -> AppStrings.direct_invite_invalid
}
