package com.folderspan.ui.components.drawer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.writeClipboardText
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadDraft
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadError
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadFieldError
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import kotlinx.coroutines.launch
import strings.AppStrings

@Composable
internal fun ClipboardUrlDownloadDialogHost(
    state: ClipboardUrlDownloadState,
    coordinator: ClipboardUrlDownloadCoordinator,
    copyText: suspend (String) -> Boolean = ::writeClipboardText,
) {
    when (state) {
        ClipboardUrlDownloadState.Idle -> Unit
        is ClipboardUrlDownloadState.Configuring -> ClipboardUrlDownloadSettingsDialog(
            state = state,
            onConfirm = coordinator::confirm,
            onDismiss = coordinator::dismiss,
        )

        is ClipboardUrlDownloadState.Inspecting,
        is ClipboardUrlDownloadState.Downloading,
        is ClipboardUrlDownloadState.RetryWaiting,
        is ClipboardUrlDownloadState.Succeeded -> Unit

        is ClipboardUrlDownloadState.Fallback -> ClipboardContentFallbackDialog(
            state = state,
            onDismiss = coordinator::dismiss,
            copyText = copyText,
        )

        ClipboardUrlDownloadState.Cancelled -> Unit
    }
}

@Composable
private fun ClipboardUrlDownloadSettingsDialog(
    state: ClipboardUrlDownloadState.Configuring,
    onConfirm: (ClipboardUrlDownloadDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(state.draft) { mutableStateOf(state.draft) }
    val capabilities = draft.capabilities
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_clipboard_url_download_title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    AppStrings.ui_clipboard_url_download_target_arg0.format(draft.targetSummary),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = draft.retries,
                    onValueChange = { draft = draft.copy(retries = it) },
                    label = { Text(AppStrings.ui_clipboard_url_download_retries) },
                    isError = state.errors.retries != null,
                    supportingText = state.errors.retries?.let { error ->
                        ({ Text(clipboardUrlDownloadFieldErrorMessage(error)) })
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CLIPBOARD_URL_RETRIES_TAG),
                )
                OutlinedTextField(
                    value = draft.cookie,
                    onValueChange = { draft = draft.copy(cookie = it) },
                    label = { Text(AppStrings.ui_clipboard_url_download_cookie) },
                    enabled = capabilities.canSetCookie,
                    isError = state.errors.cookie != null,
                    supportingText = {
                        Text(
                            state.errors.cookie?.let(::clipboardUrlDownloadFieldErrorMessage)
                                ?: if (!capabilities.canSetCookie) {
                                    AppStrings.ui_clipboard_url_download_browser_cookie_hint
                                } else {
                                    ""
                                }
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CLIPBOARD_URL_COOKIE_TAG),
                )
                OutlinedTextField(
                    value = draft.userAgent,
                    onValueChange = { draft = draft.copy(userAgent = it) },
                    label = { Text(AppStrings.ui_clipboard_url_download_user_agent) },
                    enabled = capabilities.canSetUserAgent,
                    isError = state.errors.userAgent != null,
                    supportingText = {
                        Text(
                            state.errors.userAgent?.let(::clipboardUrlDownloadFieldErrorMessage)
                                ?: if (!capabilities.canSetUserAgent) {
                                    AppStrings.ui_clipboard_url_download_browser_headers_hint
                                } else {
                                    ""
                                }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CLIPBOARD_URL_USER_AGENT_TAG),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        AppStrings.ui_clipboard_url_download_automatic_headers,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.automaticHeaders,
                        onCheckedChange = { enabled -> draft = draft.copy(automaticHeaders = enabled) },
                        enabled = capabilities.canControlAutomaticHeaders,
                        modifier = Modifier.testTag(CLIPBOARD_URL_AUTOMATIC_HEADERS_TAG),
                    )
                }
                if (!capabilities.canControlAutomaticHeaders) {
                    Text(
                        AppStrings.ui_clipboard_url_download_browser_headers_hint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = draft.threads,
                    onValueChange = { draft = draft.copy(threads = it) },
                    label = { Text(AppStrings.ui_clipboard_url_download_threads) },
                    isError = state.errors.threads != null,
                    supportingText = state.errors.threads?.let { error ->
                        ({ Text(clipboardUrlDownloadFieldErrorMessage(error)) })
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CLIPBOARD_URL_THREADS_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(draft) },
                modifier = Modifier.testTag(CLIPBOARD_URL_CONFIRM_TAG),
            ) {
                Text(AppStrings.ui_download)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}

@Composable
private fun ClipboardContentFallbackDialog(
    state: ClipboardUrlDownloadState.Fallback,
    onDismiss: () -> Unit,
    copyText: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var copyResult by remember(state.rawText) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.error == null) {
                    AppStrings.ui_clipboard_content_title
                } else {
                    AppStrings.ui_clipboard_url_download_failed
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { error ->
                    Text(
                        clipboardUrlDownloadErrorMessage(error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SelectionContainer {
                    Text(
                        text = state.rawText,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                copyResult?.let { result ->
                    Text(result, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.rawText.isNotEmpty(),
                modifier = Modifier.testTag(CLIPBOARD_CONTENT_COPY_TAG),
                onClick = {
                    scope.launch {
                        copyResult = if (copyText(state.rawText)) {
                            AppStrings.ui_copied
                        } else {
                            AppStrings.ui_copy_failed
                        }
                    }
                },
            ) {
                Text(AppStrings.ui_copy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppStrings.ui_close) }
        },
    )
}

internal fun clipboardUrlDownloadErrorMessage(error: ClipboardUrlDownloadError): String = when (error) {
    ClipboardUrlDownloadError.AuthenticationRequired -> AppStrings.ui_clipboard_url_error_authentication
    ClipboardUrlDownloadError.HtmlContent -> AppStrings.ui_clipboard_url_error_html
    ClipboardUrlDownloadError.MissingFileIdentity -> AppStrings.ui_clipboard_url_error_file_identity
    ClipboardUrlDownloadError.MissingFileSize -> AppStrings.ui_clipboard_url_error_file_size
    ClipboardUrlDownloadError.RedirectLimit,
    ClipboardUrlDownloadError.RedirectRejected -> AppStrings.ui_clipboard_url_error_redirect
    ClipboardUrlDownloadError.ReadTimeout -> AppStrings.ui_clipboard_url_error_timeout
    ClipboardUrlDownloadError.TlsFailure -> AppStrings.ui_clipboard_url_error_tls
    ClipboardUrlDownloadError.ResponseChanged,
    ClipboardUrlDownloadError.RangeRejected -> AppStrings.ui_clipboard_url_error_changed
    ClipboardUrlDownloadError.BrowserPolicy -> AppStrings.ui_clipboard_url_error_browser
    ClipboardUrlDownloadError.InvalidSettings,
    ClipboardUrlDownloadError.InvalidUrl -> AppStrings.ui_clipboard_url_error_invalid
    ClipboardUrlDownloadError.HttpFailure -> AppStrings.ui_clipboard_url_error_http
    ClipboardUrlDownloadError.TransportInterrupted -> AppStrings.ui_clipboard_url_error_transport
    ClipboardUrlDownloadError.StagingFailure -> AppStrings.ui_clipboard_url_error_staging
    ClipboardUrlDownloadError.Cancelled -> AppStrings.ui_clipboard_url_error_cancelled
    ClipboardUrlDownloadError.Unknown -> AppStrings.ui_clipboard_url_error_unknown
}

internal fun clipboardUrlDownloadFieldErrorMessage(error: ClipboardUrlDownloadFieldError): String = when (error) {
    ClipboardUrlDownloadFieldError.NotInteger -> AppStrings.ui_clipboard_url_validation_integer
    ClipboardUrlDownloadFieldError.RetryRange -> AppStrings.ui_clipboard_url_validation_retry_range
    ClipboardUrlDownloadFieldError.ThreadRange -> AppStrings.ui_clipboard_url_validation_thread_range
    ClipboardUrlDownloadFieldError.CookieTooLong -> AppStrings.ui_clipboard_url_validation_cookie_too_long
    ClipboardUrlDownloadFieldError.CookieControlCharacters ->
        AppStrings.ui_clipboard_url_validation_cookie_control
    ClipboardUrlDownloadFieldError.CookieInvalidFormat -> AppStrings.ui_clipboard_url_validation_cookie_format
    ClipboardUrlDownloadFieldError.UserAgentRequired -> AppStrings.ui_clipboard_url_validation_user_agent_required
    ClipboardUrlDownloadFieldError.UserAgentTooLong -> AppStrings.ui_clipboard_url_validation_user_agent_too_long
    ClipboardUrlDownloadFieldError.UserAgentControlCharacters ->
        AppStrings.ui_clipboard_url_validation_user_agent_control
}

internal const val CLIPBOARD_URL_RETRIES_TAG = "clipboard-url-retries"
internal const val CLIPBOARD_URL_COOKIE_TAG = "clipboard-url-cookie"
internal const val CLIPBOARD_URL_USER_AGENT_TAG = "clipboard-url-user-agent"
internal const val CLIPBOARD_URL_AUTOMATIC_HEADERS_TAG = "clipboard-url-automatic-headers"
internal const val CLIPBOARD_URL_THREADS_TAG = "clipboard-url-threads"
internal const val CLIPBOARD_URL_CONFIRM_TAG = "clipboard-url-confirm"
internal const val CLIPBOARD_CONTENT_COPY_TAG = "clipboard-content-copy"
