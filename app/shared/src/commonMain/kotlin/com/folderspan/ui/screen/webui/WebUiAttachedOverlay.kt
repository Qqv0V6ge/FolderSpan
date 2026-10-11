package com.folderspan.ui.screen.webui

import strings.AppStrings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import com.folderspan.ui.effects.OnAppResumeEffect
import com.folderspan.service.webui.DEFAULT_WEB_UI_ADDRESS
import com.folderspan.service.webui.WebUiRuntimeAddress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.FolderSpanAppTheme
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiBrowserHttp
import com.folderspan.service.webui.WebUiMode
import com.folderspan.service.webui.WebUiRemoteClient
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.ui.components.dialog.DeviceRoleSelectionDialog
import com.folderspan.ui.components.model.DeviceRoleOptionUiState
import com.folderspan.ui.components.model.DeviceRoleOptionsUiState
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.settings.SettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun WebUiAttachedOverlay() {
    if (!WebUiAttachment.isAttached) return
    val scope = rememberCoroutineScope()
    val client = remember(scope) { WebUiRemoteClient(scope) }
    OnAppResumeEffect { scope.launch { client.checkConnection() } }
    LaunchedEffect(client) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { client.disconnect() }
        }
    }
    // Theme and language changes can recreate their content; keep the connection above them.
    FolderSpanAppTheme { WebUiLoginContent(client) }
}

@Composable
private fun WebUiLoginContent(client: WebUiRemoteClient) {
    val scope = rememberCoroutineScope()
    val signedIn = WebUiAttachment.hasSession
    val connected = WebUiAttachment.connected
    val showLogin = WebUiAttachment.showConnectionDialog
    var address by remember(showLogin) {
        mutableStateOf(
            WebUiAttachment.target?.origin
                ?: WebUiBrowserHttp.selectedTarget()?.takeIf(String::isNotBlank)
                ?: DEFAULT_WEB_UI_ADDRESS,
        )
    }
    var secret by remember(showLogin) { mutableStateOf("") }
    var addressError by remember(showLogin) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val parsedTarget = remember(address) {
        runCatching { WebUiRuntimeAddress.parse(address, securePage = !WebUiBrowserHttp.isPlainHttp()) }
    }
    val changingTarget = WebUiAttachment.target == null ||
        parsedTarget.getOrNull()?.origin != WebUiAttachment.target?.origin
    if (signedIn && connected && !showLogin) {
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = AppStrings.ui_web_ui_login_title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        secret = ""
                        addressError = null
                        error = null
                    },
                    label = { Text(AppStrings.ui_web_ui_runtime_address) },
                    singleLine = true,
                    enabled = !busy,
                    isError = addressError != null,
                    supportingText = addressError?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (WebUiAttachment.stale) {
                    Text(
                        text = AppStrings.ui_web_ui_session_expired,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (parsedTarget.getOrNull()?.plaintext == true) {
                    Text(
                        text = AppStrings.ui_web_ui_login_http_warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (changingTarget) {
                    Text(AppStrings.ui_web_ui_runtime_switch_note)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    client.logout()
                                    WebUiBrowserHttp.selectTargetAndReload(parsedTarget.getOrThrow().origin)
                                } catch (failure: Exception) {
                                    addressError = failure.message
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(AppStrings.ui_web_ui_runtime_reload) }
                } else {
                    PasswordOutlinedTextField(
                        value = secret,
                        onValueChange = {
                            secret = it
                            error = null
                        },
                        label = { Text(AppStrings.ui_web_ui_login_secret) },
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (busy) return@Button
                            scope.launch {
                                busy = true
                                error = null
                                val result = client.login(secret)
                                busy = false
                                if (result.isSuccess) {
                                    secret = ""
                                    WebUiAttachment.showConnectionDialog = false
                                } else {
                                    error = result.exceptionOrNull()?.message ?: AppStrings.ui_web_ui_login_failed
                                }
                            }
                        },
                        enabled = !busy && secret.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(AppStrings.ui_web_ui_login_submit)
                    }
                }
            }
        }
    }
}

suspend fun detectWebUiAttachment() {
    WebUiAttachment.mode = WebUiMode.Attached
    val address = WebUiBrowserHttp.selectedTarget()?.takeIf(String::isNotBlank)
        ?: if (WebUiBrowserHttp.probeBootstrap()) WebUiBrowserHttp.origin() else DEFAULT_WEB_UI_ADDRESS
    WebUiAttachment.target = runCatching {
        WebUiRuntimeAddress.parse(address, securePage = !WebUiBrowserHttp.isPlainHttp())
    }.getOrNull()
    WebUiAttachment.plaintextHttp = WebUiAttachment.target?.plaintext == true
}
