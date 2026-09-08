package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val LocalEditorDialogWindowWidth = staticCompositionLocalOf<Dp?> { null }

internal fun editorDialogUsesFullScreen(windowWidth: Dp): Boolean = windowWidth < 600.dp

internal fun editorDialogProperties(
    fillScreenWidth: Boolean,
    dismissOnClickOutside: Boolean = true,
): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = !fillScreenWidth,
)

enum class EditorDialogSectionTone {
    Neutral,
    Accent,
    Warning,
    Destructive,
}

@Composable
fun EditorDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentScrollable: Boolean = true,
    forceFullScreen: Boolean? = null,
    fillScreenWidth: Boolean = false,
    titleActions: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inheritedWindowWidth = LocalEditorDialogWindowWidth.current
    val windowWidth = inheritedWindowWidth ?: run {
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    }
    val contentScrollModifier = if (contentScrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    val useFullScreen = forceFullScreen ?: editorDialogUsesFullScreen(windowWidth)

    CompositionLocalProvider(LocalEditorDialogWindowWidth provides windowWidth) {
        if (useFullScreen) {
            Dialog(
                onDismissRequest = onDismissRequest,
                properties = editorDialogProperties(
                    fillScreenWidth = true,
                    dismissOnClickOutside = false,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding()
                            .then(modifier),
                    ) {
                        if (titleActions == null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                titleActions()
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .then(contentScrollModifier),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            content = content,
                        )
                        if (actions != null) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                actions()
                            }
                        }
                    }
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                modifier = if (fillScreenWidth) {
                    Modifier.fillMaxWidth().then(modifier)
                } else {
                    modifier
                },
                title = {
                    if (titleActions == null) {
                        Text(title)
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.weight(1f),
                            )
                            titleActions()
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(contentScrollModifier),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                },
                confirmButton = { actions?.invoke() },
                shape = if (fillScreenWidth) RectangleShape else AlertDialogDefaults.shape,
                properties = editorDialogProperties(fillScreenWidth),
            )
        }
    }
}

@Composable
fun EditorDialogSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: EditorDialogSectionTone = EditorDialogSectionTone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor: Color
    val contentColor: Color
    when (tone) {
        EditorDialogSectionTone.Neutral -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            contentColor = MaterialTheme.colorScheme.onSurface
        }

        EditorDialogSectionTone.Accent -> {
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        }

        EditorDialogSectionTone.Warning -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }

        EditorDialogSectionTone.Destructive -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditorDialogSectionContent(title = title, content = content)
        }
    }
}

@Composable
private fun EditorDialogSectionContent(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        content()
    }
}

@Composable
fun EditorDialogPropertyRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LocalContentColor.current.copy(alpha = 0.78f),
            modifier = Modifier.width(104.dp),
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                ),
            )
        }
    }
}

@Composable
fun EditorDialogAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text)
    }
}
