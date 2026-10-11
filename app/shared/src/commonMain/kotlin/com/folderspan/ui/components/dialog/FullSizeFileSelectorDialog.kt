package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal const val FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG =
    "full-size-file-selector-dialog-layout"
internal const val FULL_SIZE_FILE_SELECTOR_DIALOG_HEADER_TEST_TAG =
    "full-size-file-selector-dialog-header"
internal const val FULL_SIZE_FILE_SELECTOR_DIALOG_CONTENT_TEST_TAG =
    "full-size-file-selector-dialog-content"
internal const val FULL_SIZE_FILE_SELECTOR_DIALOG_ACTIONS_TEST_TAG =
    "full-size-file-selector-dialog-actions"

/**
 * 可复用的全尺寸文件选择器弹窗壳。
 *
 * 标题和操作区固定，调用方应把 [com.folderspan.ui.components.file.FileSelectorEntryRegion]
 * 放入 [content]；选择器只会滚动自身条目区。弹窗使用全部可用宽高，并应用安全绘制区与 IME 内边距。
 */
@Composable
fun FullSizeFileSelectorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    dismissButton: @Composable RowScope.() -> Unit,
    confirmButton: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = fullSizeFileSelectorDialogProperties(),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier),
            shape = RectangleShape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            FullSizeFileSelectorDialogLayout(
                title = title,
                dismissButton = dismissButton,
                confirmButton = confirmButton,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

internal fun fullSizeFileSelectorDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
)

@Composable
internal fun FullSizeFileSelectorDialogLayout(
    title: @Composable () -> Unit,
    dismissButton: @Composable RowScope.() -> Unit,
    confirmButton: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .testTag(FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FULL_SIZE_FILE_SELECTOR_DIALOG_HEADER_TEST_TAG)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            title()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(FULL_SIZE_FILE_SELECTOR_DIALOG_CONTENT_TEST_TAG),
            content = content,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FULL_SIZE_FILE_SELECTOR_DIALOG_ACTIONS_TEST_TAG)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dismissButton()
            confirmButton()
        }
    }
}
