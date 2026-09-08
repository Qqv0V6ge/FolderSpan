package com.folderspan.ui.components.drawer

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.ClipboardContent
import com.folderspan.clipboard.ClipboardTextDispatch
import com.folderspan.clipboard.ClipboardTextPasteBus
import com.folderspan.clipboard.ClipboardTextOpenBus
import com.folderspan.clipboard.dispatchClipboardTextSnapshot
import com.folderspan.clipboard.parseClipboardContent
import com.folderspan.clipboard.readClipboardContent
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.extensions.replaceLast
import com.folderspan.ui.components.model.ClipboardPathDialogUiState
import com.folderspan.ui.components.model.buildClipboardPathDialogUiState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import com.folderspan.ui.state.main.HomeState
import com.folderspan.service.http.clipboard.ClipboardUrlDraftOpenResult
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspector
import com.folderspan.ui.state.file.ClipboardUrlShareFiles
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class ClipboardOpenState(
    private val fileState: FileState,
    private val homeState: HomeState,
    private val scope: CoroutineScope,
    private val downloadCoordinator: ClipboardUrlDownloadCoordinator,
    private val urlShareInspector: ClipboardUrlShareInspector,
) {
    var showDialog by mutableStateOf(false)
        private set
    private var candidates by mutableStateOf<List<ClipboardPathCandidate>>(emptyList())
    var dialogUiState by mutableStateOf(ClipboardPathDialogUiState(emptyList()))
        private set
    var showAlreadyRunningDialog by mutableStateOf(false)
        private set

    fun openFromClipboard() {
        scope.launch {
            val content = readClipboardContent() ?: return@launch
            dispatchContent(content, ClipboardTextEntryMode.Open)
        }
    }

    fun dispatchContent(
        content: ClipboardContent,
        entryMode: ClipboardTextEntryMode = ClipboardTextEntryMode.Paste,
    ) {
        val snapshot = ClipboardContent(
            texts = content.texts.toList(),
            filePaths = content.filePaths.toList(),
        )
        scope.launch {
            logClipboardContent(snapshot)
            val parsed = parseClipboardContent(snapshot)
            parsed.standaloneHttpUrl?.let {
                LogKit.i("Clipboard URL candidate: scheme=${it.substringBefore(':').lowercase()}")
            }

            val resolvedCandidates = withContext(Dispatchers.Default) {
                resolveClipboardCandidates(parsed.paths)
            }

            when (
                val dispatch = dispatchClipboardTextSnapshot(
                    parsed = parsed,
                    resolvedPaths = resolvedCandidates.map { candidate -> candidate.displayPath },
                )
            ) {
                ClipboardTextDispatch.Empty -> Unit
                is ClipboardTextDispatch.DownloadUrl -> {
                    when (clipboardUrlAction(entryMode)) {
                        ClipboardUrlAction.AddToShare -> {
                            if (ClipboardUrlShareFiles.inspectAndAdd(dispatch.url, urlShareInspector)) {
                                fileState.openSystemShareFiles()
                            } else {
                                LogKit.w("Clipboard URL metadata inspection failed")
                            }
                        }

                        ClipboardUrlAction.ConfigureDownload -> {
                            if (downloadCoordinator.openUrl(dispatch.rawText, dispatch.url) ==
                                ClipboardUrlDraftOpenResult.AlreadyRunning
                            ) {
                                showAlreadyRunningDialog = true
                            }
                        }
                    }
                }
                is ClipboardTextDispatch.ShowContent -> {
                    if (downloadCoordinator.showContent(dispatch.rawText) ==
                        ClipboardUrlDraftOpenResult.AlreadyRunning
                    ) {
                        showAlreadyRunningDialog = true
                    }
                }
                is ClipboardTextDispatch.ExistingPaths -> when (resolvedCandidates.size) {
                    1 -> resolvedCandidates.first().let { candidate ->
                    openPathWithCheckedFiles(listOf(candidate.fileInfo), candidate.openPath)
                    }

                    else -> {
                        val uniqueOpenPaths = resolvedCandidates
                            .map { item -> normalizeOpenPath(item.openPath) }
                            .distinct()
                        if (uniqueOpenPaths.size == 1) {
                            val openPath = resolvedCandidates.first().openPath
                            val highlightFiles = resolvedCandidates.map { item -> item.fileInfo }
                            openPathWithCheckedFiles(highlightFiles, openPath)
                        } else {
                            candidates = resolvedCandidates
                            dialogUiState = buildClipboardPathDialogUiState(resolvedCandidates)
                            showDialog = true
                        }
                    }
                }
            }
        }
    }

    fun selectCandidate(index: Int) {
        val candidate = candidates.getOrNull(index) ?: run {
            dismissDialog()
            return
        }
        dismissDialog()
        openPathWithCheckedFiles(listOf(candidate.fileInfo), candidate.openPath)
    }

    fun dismissDialog() {
        showDialog = false
        candidates = emptyList()
        dialogUiState = ClipboardPathDialogUiState(emptyList())
    }

    fun dismissAlreadyRunningDialog() {
        showAlreadyRunningDialog = false
    }

    private fun openPathWithCheckedFiles(files: Collection<FileSimpleInfo>, openPath: String) {
        homeState.applyAutoHighlight(files)
        scope.launch(Dispatchers.Default) {
            if (fileState.deskType.value is Local) {
                fileState.updatePath(openPath)
            } else {
                fileState.updateDesk(FileProtocol.Local, Local(), pathOverride = openPath)
            }
        }
    }

    private fun resolveClipboardCandidates(paths: List<String>): List<ClipboardPathCandidate> {
        val result = LinkedHashMap<String, ClipboardPathCandidate>()
        paths.forEach { rawPath ->
            val info = FileUtils.getFile(FileAccessPermission.Allowed, rawPath).getOrNull() ?: return@forEach
            val parentPath = info.path.replaceLast(info.name, "")
            val openPath = parentPath.ifBlank { info.path }
            if (openPath.isNotBlank() && info.path.isNotBlank()) {
                val displayPath = info.path
                if (!result.containsKey(displayPath)) {
                    result[displayPath] = ClipboardPathCandidate(
                        displayPath = displayPath,
                        openPath = openPath,
                        fileInfo = info
                    )
                }
            }
        }
        return result.values.toList()
    }

    private fun logClipboardContent(content: ClipboardContent) {
        LogKit.i(
            "Clipboard content: textEntries=${content.texts.size}, " +
                "textLength=${content.texts.sumOf(String::length)}, fileEntries=${content.filePaths.size}"
        )
    }
}

@Composable
internal fun rememberClipboardOpenState(
    fileState: FileState,
    homeState: HomeState,
    downloadCoordinator: ClipboardUrlDownloadCoordinator,
    urlShareInspector: ClipboardUrlShareInspector,
): ClipboardOpenState {
    val scope = rememberCoroutineScope()
    val state = remember(fileState, homeState, scope, downloadCoordinator, urlShareInspector) {
        ClipboardOpenState(fileState, homeState, scope, downloadCoordinator, urlShareInspector)
    }
    LaunchedEffect(state) {
        ClipboardTextPasteBus.events.collect { content ->
            state.dispatchContent(content, ClipboardTextEntryMode.Paste)
        }
    }
    LaunchedEffect(state) {
        ClipboardTextOpenBus.events.collect { content ->
            state.dispatchContent(content, ClipboardTextEntryMode.Open)
        }
    }
    return state
}

internal enum class ClipboardTextEntryMode {
    Open,
    Paste,
}

internal enum class ClipboardUrlAction {
    AddToShare,
    ConfigureDownload,
}

internal fun clipboardUrlAction(entryMode: ClipboardTextEntryMode): ClipboardUrlAction = when (entryMode) {
    ClipboardTextEntryMode.Open -> ClipboardUrlAction.AddToShare
    ClipboardTextEntryMode.Paste -> ClipboardUrlAction.ConfigureDownload
}

@Composable
internal fun ClipboardPathDialog(
    uiState: ClipboardPathDialogUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState) {
        if (uiState.candidates.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_select_directory_open) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp)),
                state = listState,
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(
                    items = uiState.candidates,
                    key = { _, candidate -> candidate.index }
                ) { index, candidate ->
                    ListItem(
                        headlineContent = {
                            Text(
                                candidate.displayPath,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(candidate.index) }
                    )
                    if (index != uiState.candidates.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

internal data class ClipboardPathCandidate(
    val displayPath: String,
    val openPath: String,
    val fileInfo: FileSimpleInfo
)

private fun normalizeOpenPath(path: String): String {
    val separator = PathUtils.getPathSeparator()
    var normalized = path.trim()
    if (normalized == separator) return separator
    while (normalized.endsWith(separator) && normalized.length > separator.length) {
        normalized = normalized.dropLast(separator.length)
    }
    return normalized
}
