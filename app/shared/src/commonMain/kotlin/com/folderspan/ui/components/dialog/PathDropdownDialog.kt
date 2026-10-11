package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathDropdownDialog(
    initText: String,
    directoryPaths: List<String>,
    pathSeparator: String,
    loadDirectoryPaths: suspend (String) -> Result<List<String>>,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val initialText = remember(initText, pathSeparator) {
        initText.withTrailingPathSeparator(pathSeparator)
    }
    var textFieldValue by remember(initialText) {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var isLoadingDirectories by remember { mutableStateOf(false) }
    var matchedDirectoryPaths by remember(directoryPaths) { mutableStateOf(directoryPaths) }
    val focusRequester = remember { FocusRequester() }
    val text = textFieldValue.text
    val matchedDirectoryCount = matchedDirectoryPaths.size
    val canOpenMenu = matchedDirectoryCount > 0 && !isLoadingDirectories

    AlertDialog(
        title = { Text(AppStrings.ui_modify_directory) },
        text = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = {},
            ) {
                TextField(
                    value = textFieldValue,
                    onValueChange = { value ->
                        textFieldValue = value
                        expanded = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                        .focusRequester(focusRequester),
                    label = { Text(AppStrings.ui_directory) },
                    singleLine = true,
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .alpha(if (canOpenMenu) 1f else 0.38f)
                                .clickable(
                                    enabled = canOpenMenu,
                                    role = Role.Button,
                                    onClick = { expanded = !expanded }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            BadgedBox(
                                badge = {
                                    if (matchedDirectoryCount > 0) {
                                        Badge {
                                            Text(matchedDirectoryCount.toBadgeText())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = if (expanded) AppStrings.ui_collapse_catalog_candidates else AppStrings.ui_expand_directory_candidates
                                )
                            }
                        }
                    },
                )

                DropdownMenu(
                    expanded = expanded && matchedDirectoryPaths.isNotEmpty(),
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.exposedDropdownSize(true),
                    properties = PopupProperties(focusable = false),
                ) {
                    matchedDirectoryPaths.forEach { path ->
                        DropdownMenuItem(
                            text = { Text(path.withTrailingPathSeparator(pathSeparator)) },
                            onClick = {
                                val selectedPath = path.withTrailingPathSeparator(pathSeparator)
                                textFieldValue = TextFieldValue(
                                    text = selectedPath,
                                    selection = TextRange(selectedPath.length)
                                )
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        },
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = { onConfirm(textFieldValue.text.trimTrailingPathSeparator(pathSeparator)) },
                enabled = textFieldValue.text.isNotEmpty()
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(AppStrings.ui_cancel)
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(text, directoryPaths, initText, pathSeparator) {
        expanded = false
        isLoadingDirectories = true
        matchedDirectoryPaths = resolvePathDropdownDirectories(
            text = text,
            fallbackPath = initText,
            pathSeparator = pathSeparator,
            fallbackDirectoryPaths = directoryPaths,
            loadDirectoryPaths = loadDirectoryPaths,
        )
        isLoadingDirectories = false
    }
}

private data class PathDropdownLookup(
    val parentPath: String,
    val nameFilter: String,
)

private suspend fun resolvePathDropdownDirectories(
    text: String,
    fallbackPath: String,
    pathSeparator: String,
    fallbackDirectoryPaths: List<String>,
    loadDirectoryPaths: suspend (String) -> Result<List<String>>,
): List<String> {
    val separator = pathSeparator.ifBlank { "/" }
    val trimmedText = text.trim().trimTrailingPathSeparator(separator)
    if (trimmedText.isBlank()) {
        return fallbackDirectoryPaths
    }

    loadDirectoryPaths(trimmedText).getOrNull()?.let { directories ->
        return directories
    }

    val lookup = buildPathDropdownLookup(trimmedText, fallbackPath, pathSeparator)
    val parentDirectories = if (lookup.parentPath == fallbackPath) {
        fallbackDirectoryPaths
    } else {
        loadDirectoryPaths(lookup.parentPath).getOrDefault(emptyList())
    }
    if (lookup.nameFilter.isBlank()) {
        return parentDirectories
    }

    return parentDirectories.filter { path ->
        path.substringAfterLast(pathSeparator).contains(lookup.nameFilter, ignoreCase = true)
    }
}

private fun buildPathDropdownLookup(
    text: String,
    fallbackPath: String,
    pathSeparator: String,
): PathDropdownLookup {
    val separator = pathSeparator.ifBlank { "/" }
    val lookupText = text.trimTrailingPathSeparator(separator)
    if (text.endsWith(separator)) {
        return PathDropdownLookup(lookupText, "")
    }

    val separatorIndex = lookupText.lastIndexOf(separator)
    if (separatorIndex < 0) {
        return PathDropdownLookup(fallbackPath, lookupText)
    }

    val parentPath = when (separator) {
        "/" if separatorIndex == 0 -> separator
        "\\" if separatorIndex == 2 && lookupText.getOrNull(1) == ':' ->
            lookupText.substring(0, separatorIndex + separator.length)
        else -> lookupText.substring(0, separatorIndex).ifBlank { separator }
    }
    val nameFilter = lookupText.substring(separatorIndex + separator.length)
    return PathDropdownLookup(parentPath, nameFilter)
}

private fun Int.toBadgeText(): String {
    return if (this > 99) "99+" else toString()
}

private fun String.withTrailingPathSeparator(pathSeparator: String): String {
    val separator = pathSeparator.ifBlank { "/" }
    if (isBlank() || endsWith(separator) || isRootPath(separator)) {
        return this
    }
    return this + separator
}

private fun String.trimTrailingPathSeparator(pathSeparator: String): String {
    val separator = pathSeparator.ifBlank { "/" }
    if (isRootPath(separator)) {
        return this
    }
    return removeSuffix(separator)
}

private fun String.isRootPath(pathSeparator: String): Boolean {
    val separator = pathSeparator.ifBlank { "/" }
    return this == separator || (separator == "\\" && length == 3 && this[1] == ':' && endsWith(separator))
}
