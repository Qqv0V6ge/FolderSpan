package com.folderspan.ui.screen.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.error.ErrorBase
import com.folderspan.ui.components.error.ErrorEmptyData
import com.folderspan.ui.components.loading.LoadingBase
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.components.status.StatusWidget
import com.folderspan.ui.components.status.StatusWidgetDefaults
import com.folderspan.utils.WindowPaneMode
import com.folderspan.utils.WindowSizeClass
import com.folderspan.utils.calculateWindowSizeClass
import strings.AppStrings

internal data class ReferenceFileUiModel(
    val id: String,
    val name: String,
    val path: String,
    val typeLabel: String,
    val sizeLabel: String,
    val modifiedLabel: String,
    val isDirectory: Boolean,
)

internal data class PageDesignReferenceUiState(
    val files: List<ReferenceFileUiModel> = emptyList(),
    val selectedFileId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedFile: ReferenceFileUiModel?
        get() = files.firstOrNull { it.id == selectedFileId }
}

internal fun PageDesignReferenceUiState.toPageViewState(): PageViewState =
    resolvePageViewState(
        isLoading = isLoading,
        errorState = errorMessage?.let { message ->
            PageErrorState(
                type = PageErrorType.General,
                message = message,
            )
        },
        isEmpty = files.isEmpty(),
    )

internal data class PageDesignReferenceText(
    val pageTitle: String,
    val listTitle: String,
    val detailTitle: String,
    val selectFilePrompt: String,
    val emptyFilesMessage: String,
    val loadingDescription: String,
    val errorTitle: String,
    val retryLabel: String,
    val backDescription: String,
    val sizeLabel: String,
    val typeLabel: String,
    val modifiedLabel: String,
    val shareLabel: String,
    val deleteLabel: String,
)

/**
 * 由各平台宿主根据当前 Compose 窗口提供，而不是根据 Android、iOS、Desktop 或 Web 名称分支。
 * [separatingPaneGap] 用于避开双屏接缝或折叠屏铰链；普通窗口保持为 0.dp。
 */
internal data class ReferencePageWindowInfo(
    val widthSizeClass: WindowSizeClass,
    val separatingPaneGap: Dp = 0.dp,
)

internal data class ReferencePageLayoutSpec(
    val paneMode: WindowPaneMode,
    val paneSpacing: Dp,
    val listPaneWidth: Dp,
)

internal fun referencePageWindowInfo(
    maxWidth: Dp,
    maxHeight: Dp,
    separatingPaneGap: Dp = 0.dp,
): ReferencePageWindowInfo = ReferencePageWindowInfo(
    widthSizeClass = calculateWindowSizeClass(maxWidth, maxHeight),
    separatingPaneGap = separatingPaneGap,
)

internal fun referencePageLayout(windowInfo: ReferencePageWindowInfo): ReferencePageLayoutSpec {
    return when (windowInfo.widthSizeClass) {
        WindowSizeClass.Compact -> ReferencePageLayoutSpec(
            paneMode = WindowPaneMode.SinglePane,
            paneSpacing = 0.dp,
            listPaneWidth = 0.dp,
        )

        WindowSizeClass.Medium -> ReferencePageLayoutSpec(
            paneMode = WindowPaneMode.TwoPane,
            paneSpacing = windowInfo.separatingPaneGap,
            listPaneWidth = 240.dp,
        )

        WindowSizeClass.Expanded -> ReferencePageLayoutSpec(
            paneMode = WindowPaneMode.TwoPane,
            paneSpacing = windowInfo.separatingPaneGap,
            listPaneWidth = 320.dp,
        )

        WindowSizeClass.Large -> ReferencePageLayoutSpec(
            paneMode = WindowPaneMode.TwoPane,
            paneSpacing = windowInfo.separatingPaneGap,
            listPaneWidth = 360.dp,
        )

        WindowSizeClass.ExtraLarge -> ReferencePageLayoutSpec(
            paneMode = WindowPaneMode.TwoPane,
            paneSpacing = windowInfo.separatingPaneGap,
            listPaneWidth = 400.dp,
        )
    }
}

@Composable
internal fun PageDesignReferencePage(
    state: PageDesignReferenceUiState,
    text: PageDesignReferenceText,
    windowInfo: ReferencePageWindowInfo,
    onFileSelected: (String) -> Unit,
    onBackToList: () -> Unit,
    onRetry: () -> Unit,
    onShare: (ReferenceFileUiModel) -> Unit,
    onDelete: (ReferenceFileUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = referencePageLayout(windowInfo)
    val selectedFile = state.selectedFile
    val showingCompactDetail =
        layout.paneMode == WindowPaneMode.SinglePane && selectedFile != null

    ReferencePageTemplate(
        modifier = modifier,
        topBar = {
            ReferenceTopBarWidget(
                title = if (showingCompactDetail) text.detailTitle else text.pageTitle,
                showBack = showingCompactDetail,
                onBack = onBackToList,
                backDescription = text.backDescription,
            )
        },
    ) { scaffoldPadding ->
        ReferencePageContent(
            state = state,
            text = text,
            layout = layout,
            onFileSelected = onFileSelected,
            onRetry = onRetry,
            onShare = onShare,
            onDelete = onDelete,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        )
    }
}

@Composable
private fun ReferencePageTemplate(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppScaffold(
        modifier = modifier,
        topBar = topBar,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceTopBarWidget(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    backDescription: String,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backDescription,
                    )
                }
            }
        },
    )
}

@Composable
private fun ReferencePageContent(
    state: PageDesignReferenceUiState,
    text: PageDesignReferenceText,
    layout: ReferencePageLayoutSpec,
    onFileSelected: (String) -> Unit,
    onRetry: () -> Unit,
    onShare: (ReferenceFileUiModel) -> Unit,
    onDelete: (ReferenceFileUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.paneMode == WindowPaneMode.SinglePane) {
        val selectedFile = state.selectedFile
        if (selectedFile == null) {
            FileListPaneWidget(
                state = state,
                text = text,
                onFileSelected = onFileSelected,
                onRetry = onRetry,
                modifier = modifier,
            )
        } else {
            FileDetailPaneWidget(
                file = selectedFile,
                text = text,
                onShare = onShare,
                onDelete = onDelete,
                modifier = modifier,
            )
        }
        return
    }

    Row(
        modifier = modifier,
    ) {
        FileListPaneWidget(
            state = state,
            text = text,
            onFileSelected = onFileSelected,
            onRetry = onRetry,
            modifier = Modifier
                .width(layout.listPaneWidth)
                .fillMaxHeight(),
        )
        if (layout.paneSpacing > 0.dp) {
            Spacer(
                modifier = Modifier
                    .width(layout.paneSpacing)
                    .fillMaxHeight(),
            )
        } else {
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        FileDetailPaneWidget(
            file = state.selectedFile,
            text = text,
            onShare = onShare,
            onDelete = onDelete,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun FileListPaneWidget(
    state: PageDesignReferenceUiState,
    text: PageDesignReferenceText,
    onFileSelected: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = text.listTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(16.dp)
                .semantics { heading() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PageStateLayout(
            state = state.toPageViewState(),
            onRetry = onRetry,
            loading = {
                LoadingBase(loadingText = text.loadingDescription)
            },
            error = { error ->
                ErrorBase(
                    text = text.errorTitle,
                    supportingText = error.message,
                    actionLabel = text.retryLabel,
                    onAction = onRetry,
                )
            },
            empty = { message ->
                ErrorEmptyData(message = message ?: text.emptyFilesMessage)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = state.files,
                    key = { _, file -> file.id },
                ) { index, file ->
                    FileListItemWidget(
                        file = file,
                        selected = file.id == state.selectedFileId,
                        onClick = { onFileSelected(file.id) },
                    )
                    if (index < state.files.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItemWidget(
    file: ReferenceFileUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (selected) colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurface
    val supportingColor = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant

    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(file.path) },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Button,
            ),
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = contentColor,
            supportingColor = supportingColor,
            leadingIconColor = contentColor,
            trailingIconColor = supportingColor,
        ),
    )
}

@Composable
private fun FileDetailPaneWidget(
    file: ReferenceFileUiModel?,
    text: PageDesignReferenceText,
    onShare: (ReferenceFileUiModel) -> Unit,
    onDelete: (ReferenceFileUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (file == null) {
        ReferenceEmptyDetailWidget(
            message = text.selectFilePrompt,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val fullWidthContentModifier = Modifier.fillMaxWidth()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "file-heading") {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = fullWidthContentModifier,
            ) {
                Icon(
                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "file-heading-divider") {
            HorizontalDivider(
                modifier = fullWidthContentModifier,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        item(key = "file-metadata") {
            FileMetadataWidget(
                file = file,
                text = text,
                modifier = fullWidthContentModifier,
            )
        }

        item(key = "file-actions-divider") {
            HorizontalDivider(
                modifier = fullWidthContentModifier,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        item(key = "file-actions") {
            FileActionBarWidget(
                shareLabel = text.shareLabel,
                deleteLabel = text.deleteLabel,
                onShare = { onShare(file) },
                onDelete = { onDelete(file) },
                modifier = fullWidthContentModifier,
            )
        }
    }
}

@Composable
private fun FileMetadataWidget(
    file: ReferenceFileUiModel,
    text: PageDesignReferenceText,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ReferenceMetadataRowWidget(label = text.sizeLabel, value = file.sizeLabel)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReferenceMetadataRowWidget(label = text.typeLabel, value = file.typeLabel)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReferenceMetadataRowWidget(label = text.modifiedLabel, value = file.modifiedLabel)
    }
}

@Composable
private fun ReferenceMetadataRowWidget(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun FileActionBarWidget(
    shareLabel: String,
    deleteLabel: String,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onShare,
            modifier = Modifier.weight(1f),
        ) {
            Icon(imageVector = Icons.Default.Share, contentDescription = null)
            Text(
                text = shareLabel,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null)
            Text(
                text = deleteLabel,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ReferenceEmptyDetailWidget(
    message: String,
    modifier: Modifier = Modifier,
) {
    StatusWidget(
        title = message,
        modifier = modifier,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(StatusWidgetDefaults.VisualSize),
        )
    }
}

private val previewText = PageDesignReferenceText(
    pageTitle = AppStrings.ui_file,
    listTitle = AppStrings.ui_file,
    detailTitle = AppStrings.ui_file_details,
    selectFilePrompt = AppStrings.ui_select_the_file_to_view_details,
    emptyFilesMessage = AppStrings.ui_no_files,
    loadingDescription = AppStrings.ui_the_file_is_being_loaded,
    errorTitle = AppStrings.ui_file_loading_failed,
    retryLabel = AppStrings.ui_try_again,
    backDescription = AppStrings.ui_return_file_list,
    sizeLabel = AppStrings.ui_size,
    typeLabel = AppStrings.ui_type,
    modifiedLabel = AppStrings.ui_modification_time,
    shareLabel = AppStrings.ui_share,
    deleteLabel = AppStrings.ui_delete,
)

private val previewFiles = listOf(
    ReferenceFileUiModel(
        id = "documents",
        name = "Documents",
        path = "/Documents",
        typeLabel = AppStrings.ui_folder,
        sizeLabel = "—",
        modifiedLabel = "2026-08-09 10:30",
        isDirectory = true,
    ),
    ReferenceFileUiModel(
        id = "design-spec",
        name = "PAGE_DESIGN_GUIDELINES.md",
        path = "/Documents/PAGE_DESIGN_GUIDELINES.md",
        typeLabel = "Markdown",
        sizeLabel = "18 KB",
        modifiedLabel = "2026-08-09 11:20",
        isDirectory = false,
    ),
    ReferenceFileUiModel(
        id = "release-notes",
        name = "release-notes.txt",
        path = "/Documents/release-notes.txt",
        typeLabel = AppStrings.ui_file_type_text,
        sizeLabel = "6 KB",
        modifiedLabel = "2026-08-08 18:45",
        isDirectory = false,
    ),
)

@Composable
private fun PageDesignReferencePreview(
    initiallySelected: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedFileId by remember {
        mutableStateOf(previewFiles.firstOrNull()?.id.takeIf { initiallySelected })
    }

    MaterialTheme {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            PageDesignReferencePage(
                state = PageDesignReferenceUiState(
                    files = previewFiles,
                    selectedFileId = selectedFileId,
                ),
                text = previewText,
                windowInfo = referencePageWindowInfo(maxWidth, maxHeight),
                onFileSelected = { selectedFileId = it },
                onBackToList = { selectedFileId = null },
                onRetry = {},
                onShare = {},
                onDelete = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "Window - Compact portrait", widthDp = 360, heightDp = 800)
@Composable
private fun PageDesignCompactPreview() {
    PageDesignReferencePreview(initiallySelected = false)
}

@Preview(name = "Window - Medium short landscape", widthDp = 640, heightDp = 360)
@Composable
private fun PageDesignShortLandscapePreview() {
    PageDesignReferencePreview(initiallySelected = true)
}

@Preview(name = "Window - Medium portrait", widthDp = 720, heightDp = 900)
@Composable
private fun PageDesignMediumPreview() {
    PageDesignReferencePreview(initiallySelected = true)
}

@Preview(name = "Window - Expanded landscape", widthDp = 1024, heightDp = 768)
@Composable
private fun PageDesignExpandedPreview() {
    PageDesignReferencePreview(initiallySelected = true)
}

@Preview(name = "Window - Large", widthDp = 1366, heightDp = 900)
@Composable
private fun PageDesignLargePreview() {
    PageDesignReferencePreview(initiallySelected = true)
}

@Preview(name = "Window - Extra-large", widthDp = 1600, heightDp = 900)
@Composable
private fun PageDesignExtraLargePreview() {
    PageDesignReferencePreview(initiallySelected = true)
}
