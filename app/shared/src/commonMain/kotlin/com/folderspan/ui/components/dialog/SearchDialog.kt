package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction

/**
 * 通用的搜索弹窗组件，用于替换各页面重复的对话框实现。
 */
@Composable
fun SearchDialog(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    confirmText: String = AppStrings.ui_search,
    dismissText: String? = AppStrings.ui_cancel,
    onDismissButtonClick: (() -> Unit)? = null,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = { Icon(Icons.Default.Search, contentDescription = null) },
    showClearButton: Boolean = true,
    onClear: (() -> Unit)? = null,
    clearIcon: ImageVector = Icons.Default.Clear,
    focusOnShow: Boolean = true,
    syncQueryOnShow: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.None),
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (focusOnShow) {
            focusRequester.requestFocus()
        }
        if (syncQueryOnShow) {
            onQueryChange(query)
        }
    }

    val dismissHandler = onDismissButtonClick ?: onDismissRequest
    val clearHandler = onClear ?: { onQueryChange("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = label?.let { labelText -> { Text(labelText) } },
                placeholder = placeholder?.let { placeholderText -> { Text(placeholderText) } },
                leadingIcon = leadingIcon,
                trailingIcon = {
                    if (showClearButton && query.isNotEmpty()) {
                        IconButton(onClick = clearHandler) {
                            Icon(clearIcon, contentDescription = null)
                        }
                    }
                },
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                TextButton(onClick = dismissHandler) {
                    Text(text)
                }
            }
        }
    )
}
