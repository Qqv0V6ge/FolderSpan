package com.folderspan.pro.core.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal data class ProSnackbarPrompt(
    override val message: String,
    val tone: AuthStatusTone = AuthStatusTone.Info,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration,
) : SnackbarVisuals

internal fun proSnackbarPrompt(
    message: String?,
    tone: AuthStatusTone = AuthStatusTone.Info,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration? = null,
): ProSnackbarPrompt? {
    val cleanMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleanActionLabel = actionLabel?.trim()?.takeIf { it.isNotEmpty() }
    return ProSnackbarPrompt(
        message = cleanMessage,
        tone = tone,
        actionLabel = cleanActionLabel,
        withDismissAction = withDismissAction,
        duration = duration ?: defaultSnackbarDuration(tone, cleanActionLabel),
    )
}

@Composable
internal fun ProSnackbarEffect(
    hostState: SnackbarHostState,
    prompt: ProSnackbarPrompt?,
) {
    LaunchedEffect(hostState, prompt) {
        if (prompt != null) {
            hostState.showProSnackbar(prompt)
        }
    }
}

internal suspend fun SnackbarHostState.showProSnackbar(
    prompt: ProSnackbarPrompt,
): SnackbarResult = showSnackbar(prompt)

internal suspend fun SnackbarHostState.showProSnackbar(
    message: String?,
    tone: AuthStatusTone = AuthStatusTone.Info,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration? = null,
): SnackbarResult {
    val prompt = proSnackbarPrompt(
        message = message,
        tone = tone,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
    ) ?: return SnackbarResult.Dismissed
    return showProSnackbar(prompt)
}

@Composable
internal fun ProSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { snackbarData ->
        val tone = (snackbarData.visuals as? ProSnackbarPrompt)?.tone ?: AuthStatusTone.Info
        val contentColor = tone.snackbarContentColor()
        Snackbar(
            snackbarData = snackbarData,
            containerColor = tone.snackbarContainerColor(),
            contentColor = contentColor,
            actionColor = contentColor,
            actionContentColor = contentColor,
            dismissActionContentColor = contentColor,
        )
    }
}

private fun defaultSnackbarDuration(
    tone: AuthStatusTone,
    actionLabel: String?,
): SnackbarDuration = when {
    actionLabel != null -> SnackbarDuration.Long
    tone == AuthStatusTone.Error -> SnackbarDuration.Long
    else -> SnackbarDuration.Short
}

@Composable
private fun AuthStatusTone.snackbarContainerColor(): Color = when (this) {
    AuthStatusTone.Info -> MaterialTheme.colorScheme.primaryContainer
    AuthStatusTone.Success -> MaterialTheme.colorScheme.tertiaryContainer
    AuthStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun AuthStatusTone.snackbarContentColor(): Color = when (this) {
    AuthStatusTone.Info -> MaterialTheme.colorScheme.onPrimaryContainer
    AuthStatusTone.Success -> MaterialTheme.colorScheme.onTertiaryContainer
    AuthStatusTone.Error -> MaterialTheme.colorScheme.onErrorContainer
}
