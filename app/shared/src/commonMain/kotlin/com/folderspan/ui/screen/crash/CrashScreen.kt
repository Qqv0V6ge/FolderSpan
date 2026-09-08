package com.folderspan.ui.screen.crash

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.writeClipboardText
import com.folderspan.crash.exitApp
import com.folderspan.ui.state.main.CrashScreenState
import kotlinx.coroutines.launch

@Composable
fun CrashScreen(
    state: CrashScreenState,
    onRestart: () -> Unit,
    showFeedback: Boolean = false,
    onFeedback: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = AppStrings.ui_exception_occurred_application,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = AppStrings.ui_unhandled_exception_occurred_application_crash_page_has_been_entered,
                style = MaterialTheme.typography.bodyMedium
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val scrollState = rememberScrollState()
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = state.reportText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showFeedback) {
                    TextButton(
                        onClick = onFeedback,
                        modifier = Modifier.testTag("crash-feedback"),
                    ) {
                        Text(AppStrings.ui_crash_send_feedback)
                    }
                }
                Button(
                    enabled = state.canRestart,
                    onClick = onRestart
                ) {
                    Text(AppStrings.ui_restart_application)
                }
                OutlinedButton(
                    enabled = state.canCopy,
                    onClick = {
                        scope.launch {
                            val copied = writeClipboardText(state.reportText)
                            snackbarHostState.showSnackbar(
                                if (copied) AppStrings.ui_error_message_copied else AppStrings.ui_copy_failed
                            )
                        }
                    }
                ) {
                    Text(AppStrings.ui_copy_error)
                }
                TextButton(
                    enabled = state.canExit,
                    onClick = { exitApp() }
                ) {
                    Text(AppStrings.ui_exit)
                }
            }
        }
    }
}
