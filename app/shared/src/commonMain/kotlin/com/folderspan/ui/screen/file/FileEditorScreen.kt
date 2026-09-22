package com.folderspan.ui.screen.file

import strings.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.editor.EditorEncodingConfidence
import com.folderspan.editor.ApplicationPrivateEditorLineIndexCache
import com.folderspan.editor.ApplicationPrivateEditorBackupStore
import com.folderspan.editor.ApplicationPrivateEditorRecoveryJournalStore
import com.folderspan.editor.EditorNewlineKind
import com.folderspan.editor.EditorModification
import com.folderspan.editor.EditorModificationKind
import com.folderspan.editor.EditorSearchMode
import com.folderspan.editor.EditorSearchDirection
import com.folderspan.editor.EditorSearchHistoryEntry
import com.folderspan.editor.EditorSearchHistoryStore
import com.folderspan.editor.EditorSearchRange
import com.folderspan.editor.EditorSearchRequest
import com.folderspan.editor.EditorSearchResult
import com.folderspan.editor.EditorReplaceRequest
import com.folderspan.editor.EditorSavePreview
import com.folderspan.editor.EditorBackupMode
import com.folderspan.editor.EditorTextEncoding
import com.folderspan.editor.EditorStatisticsRequest
import com.folderspan.editor.EditorStatisticsScope
import com.folderspan.editor.editorFileInformation
import com.folderspan.editor.FileEditorDocument
import com.folderspan.editor.FileEditorDocumentState
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.components.dialog.EditorDialog
import com.folderspan.ui.components.dialog.EditorDialogAction
import com.folderspan.ui.components.dialog.EditorDialogPropertyRow
import com.folderspan.ui.components.dialog.EditorDialogSection
import com.folderspan.ui.components.dialog.EditorDialogSectionTone
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.WindowSizeClass
import com.folderspan.utils.calculateWindowSizeClass
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToSyncDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.russhwolf.settings.Settings
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val EDITOR_PAGE_SIZE = 32 * 1024
private const val MAX_CACHED_EDITOR_PAGES = 3
private const val MAX_SEARCH_QUERY_LENGTH = 256
private const val EDITOR_PAGE_TURN_COOLDOWN_MILLIS = 350L
private const val EDITOR_PAGE_TURN_WHEEL_IDLE_MILLIS = 160L
internal const val EDITOR_SEARCH_DIALOG_ALWAYS_FULL_SCREEN = true

private val EditorPageTurnThreshold = 48.dp

private enum class EditorJumpTarget {
    Page,
    Line,
    ByteOffset,
    Percentage,
}

private enum class EditorSearchScope {
    CurrentPage,
    Selection,
    WholeFile,
}

@Stable
internal class FileEditorPresentationState(
    readBoolean: (String, Boolean) -> Boolean = { _, defaultValue -> defaultValue },
    private val writeBoolean: (String, Boolean) -> Unit = { _, _ -> },
) {
    var searchExpanded by mutableStateOf(false)
        private set
    var showEncodingDialog by mutableStateOf(false)
    var showNewlineDialog by mutableStateOf(false)
    var showLineNumbers by mutableStateOf(
        readBoolean(SettingsUtils.KEY_EDITOR_SHOW_LINE_NUMBERS, true)
    )
        private set
    var automaticWrap by mutableStateOf(
        readBoolean(SettingsUtils.KEY_EDITOR_AUTOMATIC_WRAP, true)
    )
        private set
    var showInvisibleCharacters by mutableStateOf(false)

    fun toggleSearch() {
        searchExpanded = !searchExpanded
    }

    fun dismissSearch() {
        searchExpanded = false
    }

    fun toggleShowLineNumbers() {
        showLineNumbers = !showLineNumbers
        writeBoolean(SettingsUtils.KEY_EDITOR_SHOW_LINE_NUMBERS, showLineNumbers)
    }

    fun toggleAutomaticWrap() {
        automaticWrap = !automaticWrap
        writeBoolean(SettingsUtils.KEY_EDITOR_AUTOMATIC_WRAP, automaticWrap)
    }
}

internal enum class EditorSearchPresentation {
    Dialog,
    SupportingPane,
}

internal enum class EditorPageTurnDirection {
    Previous,
    Next,
}

internal enum class EditorPageTurnPhase {
    Pulling,
    Ready,
    Loading,
}

internal data class EditorPageTurnIndicatorState(
    val direction: EditorPageTurnDirection? = null,
    val progress: Float = 0f,
    val phase: EditorPageTurnPhase = EditorPageTurnPhase.Pulling,
)

internal fun pageTurnLoadingFinished(
    targetPage: Long?,
    currentPage: Long,
    isLoading: Boolean,
): Boolean = targetPage != null &&
    !isLoading &&
    currentPage == targetPage

private data class EditorPendingPageLanding(
    val pageIndex: Long,
    val atEnd: Boolean,
)

internal fun editorPageTurnDirection(
    availableScrollY: Float,
    currentPage: Long,
    pageCount: Long,
    enabled: Boolean,
): EditorPageTurnDirection? = when {
    !enabled || availableScrollY == 0f -> null
    availableScrollY > 0f && currentPage > 0L -> EditorPageTurnDirection.Previous
    availableScrollY < 0f && currentPage + 1L < pageCount -> EditorPageTurnDirection.Next
    else -> null
}

internal fun editorPageTurnWheelDirection(
    scrollY: Float,
    atStart: Boolean,
    atEnd: Boolean,
    currentPage: Long,
    pageCount: Long,
    enabled: Boolean,
): EditorPageTurnDirection? {
    val boundaryDirection = when {
        scrollY < 0f && atStart -> EditorPageTurnDirection.Previous
        scrollY > 0f && atEnd -> EditorPageTurnDirection.Next
        else -> null
    } ?: return null
    val availableDirection = editorPageTurnDirection(
        availableScrollY = when (boundaryDirection) {
            EditorPageTurnDirection.Previous -> 1f
            EditorPageTurnDirection.Next -> -1f
        },
        currentPage = currentPage,
        pageCount = pageCount,
        enabled = enabled,
    )
    return boundaryDirection.takeIf { it == availableDirection }
}

internal fun editorPageTurnIndicatorState(
    direction: EditorPageTurnDirection?,
    accumulatedDistance: Float,
    threshold: Float,
    phase: EditorPageTurnPhase? = null,
): EditorPageTurnIndicatorState {
    if (direction == null) return EditorPageTurnIndicatorState()
    val progress = if (threshold > 0f) {
        (accumulatedDistance / threshold).coerceIn(0f, 1f)
    } else {
        0f
    }
    return EditorPageTurnIndicatorState(
        direction = direction,
        progress = progress,
        phase = phase ?: if (progress >= 1f) {
            EditorPageTurnPhase.Ready
        } else {
            EditorPageTurnPhase.Pulling
        },
    )
}

internal data class EditorPageTurnGestureUpdate(
    val consumedScrollY: Float,
    val indicatorState: EditorPageTurnIndicatorState,
)

internal class EditorPageTurnGestureTracker(
    private val threshold: Float,
) {
    private var direction: EditorPageTurnDirection? = null
    private var accumulatedDistance = 0f

    val indicatorState: EditorPageTurnIndicatorState
        get() = editorPageTurnIndicatorState(
            direction = direction,
            accumulatedDistance = accumulatedDistance,
            threshold = threshold,
        )

    fun pull(
        direction: EditorPageTurnDirection,
        distance: Float,
    ): EditorPageTurnIndicatorState {
        if (this.direction != direction) {
            accumulatedDistance = 0f
            this.direction = direction
        }
        accumulatedDistance += abs(distance)
        return indicatorState
    }

    fun consumeReverse(scrollY: Float): EditorPageTurnGestureUpdate {
        val currentDirection = direction
            ?: return EditorPageTurnGestureUpdate(0f, EditorPageTurnIndicatorState())
        val reversing = when (currentDirection) {
            EditorPageTurnDirection.Previous -> scrollY < 0f
            EditorPageTurnDirection.Next -> scrollY > 0f
        }
        if (!reversing || accumulatedDistance <= 0f) {
            return EditorPageTurnGestureUpdate(0f, indicatorState)
        }

        val consumedMagnitude = min(abs(scrollY), accumulatedDistance)
        accumulatedDistance -= consumedMagnitude
        if (accumulatedDistance <= 0f) {
            reset()
        }
        return EditorPageTurnGestureUpdate(
            consumedScrollY = if (scrollY < 0f) -consumedMagnitude else consumedMagnitude,
            indicatorState = indicatorState,
        )
    }

    fun release(): EditorPageTurnDirection? {
        val releasedDirection = direction.takeIf {
            threshold > 0f && accumulatedDistance >= threshold
        }
        reset()
        return releasedDirection
    }

    fun reset(): EditorPageTurnIndicatorState {
        direction = null
        accumulatedDistance = 0f
        return EditorPageTurnIndicatorState()
    }
}

internal fun editorPageTurnIndicatorMessage(state: EditorPageTurnIndicatorState): String =
    when (state.phase) {
        EditorPageTurnPhase.Pulling -> when (state.direction) {
            EditorPageTurnDirection.Previous -> AppStrings.ui_pull_down_switch_previous_page
            EditorPageTurnDirection.Next -> AppStrings.ui_pull_up_switch_next_page
            null -> ""
        }

        EditorPageTurnPhase.Ready -> when (state.direction) {
            EditorPageTurnDirection.Previous -> AppStrings.ui_release_switch_previous_page
            EditorPageTurnDirection.Next -> AppStrings.ui_release_switch_next_page
            null -> ""
        }

        EditorPageTurnPhase.Loading -> when (state.direction) {
            EditorPageTurnDirection.Previous -> AppStrings.ui_loading_previous_page
            EditorPageTurnDirection.Next -> AppStrings.ui_loading_next_page
            null -> ""
        }
    }

private class EditorPageTurnScrollHandler(
    val nestedScrollConnection: NestedScrollConnection,
    val onWheelEvent: (
        scrollY: Float,
        atStart: Boolean,
        atEnd: Boolean,
    ) -> Boolean,
    val onWheelEventFinished: () -> Unit,
)

@Composable
private fun rememberEditorPageTurnScrollHandler(
    currentPage: Long,
    pageCount: Long,
    enabled: Boolean,
    onIndicatorStateChange: (EditorPageTurnIndicatorState) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
): EditorPageTurnScrollHandler {
    val thresholdPx = with(LocalDensity.current) { EditorPageTurnThreshold.toPx() }
    val scope = rememberCoroutineScope()
    val latestCurrentPage by rememberUpdatedState(currentPage)
    val latestPageCount by rememberUpdatedState(pageCount)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnIndicatorStateChange by rememberUpdatedState(onIndicatorStateChange)
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)
    val latestOnNextPage by rememberUpdatedState(onNextPage)

    return remember(thresholdPx, scope) {
        val gestureTracker = EditorPageTurnGestureTracker(thresholdPx)
        var pageTurnLocked = false
        var wheelEventInProgress = false
        var wheelEventHandledAtBoundary = false
        var wheelReleaseJob: Job? = null

        fun releasePageTurn(): Boolean {
            if (pageTurnLocked || !latestEnabled) {
                gestureTracker.reset()
                return false
            }
            val direction = gestureTracker.release()
            if (direction == null) {
                latestOnIndicatorStateChange(EditorPageTurnIndicatorState())
                return false
            }
            val availableDirection = editorPageTurnDirection(
                availableScrollY = when (direction) {
                    EditorPageTurnDirection.Previous -> 1f
                    EditorPageTurnDirection.Next -> -1f
                },
                currentPage = latestCurrentPage,
                pageCount = latestPageCount,
                enabled = latestEnabled,
            )
            if (availableDirection != direction) {
                latestOnIndicatorStateChange(EditorPageTurnIndicatorState())
                return false
            }

            pageTurnLocked = true
            latestOnIndicatorStateChange(
                editorPageTurnIndicatorState(
                    direction = direction,
                    accumulatedDistance = thresholdPx,
                    threshold = thresholdPx,
                    phase = EditorPageTurnPhase.Loading,
                )
            )
            when (direction) {
                EditorPageTurnDirection.Previous -> latestOnPreviousPage()
                EditorPageTurnDirection.Next -> latestOnNextPage()
            }
            scope.launch {
                delay(EDITOR_PAGE_TURN_COOLDOWN_MILLIS)
                pageTurnLocked = false
            }
            return true
        }

        fun scheduleWheelRelease() {
            wheelReleaseJob?.cancel()
            wheelReleaseJob = scope.launch {
                delay(EDITOR_PAGE_TURN_WHEEL_IDLE_MILLIS)
                wheelReleaseJob = null
                releasePageTurn()
            }
        }

        val connection = object : NestedScrollConnection {

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || pageTurnLocked) return Offset.Zero
                val update = gestureTracker.consumeReverse(available.y)
                if (update.consumedScrollY != 0f) {
                    latestOnIndicatorStateChange(update.indicatorState)
                }
                return Offset(0f, update.consumedScrollY)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (pageTurnLocked) return Offset.Zero

                val direction = editorPageTurnDirection(
                    availableScrollY = available.y,
                    currentPage = latestCurrentPage,
                    pageCount = latestPageCount,
                    enabled = latestEnabled,
                )
                if (direction == null) {
                    if (!wheelEventHandledAtBoundary &&
                        gestureTracker.indicatorState.direction != null
                    ) {
                        latestOnIndicatorStateChange(gestureTracker.reset())
                    }
                    return Offset.Zero
                }
                if (wheelEventHandledAtBoundary) {
                    return Offset(0f, available.y)
                }
                latestOnIndicatorStateChange(
                    gestureTracker.pull(
                        direction = direction,
                        distance = available.y,
                    )
                )
                if (wheelEventInProgress) {
                    scheduleWheelRelease()
                }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                wheelReleaseJob?.cancel()
                wheelReleaseJob = null
                return if (releasePageTurn()) Velocity(0f, available.y) else Velocity.Zero
            }
        }

        EditorPageTurnScrollHandler(
            nestedScrollConnection = connection,
            onWheelEvent = wheelEvent@{ scrollY, atStart, atEnd ->
                wheelEventInProgress = true
                wheelEventHandledAtBoundary = false
                if (pageTurnLocked || !latestEnabled) {
                    wheelEventInProgress = false
                    return@wheelEvent false
                }
                val hadActiveGesture = gestureTracker.indicatorState.direction != null
                val boundaryDirection = editorPageTurnWheelDirection(
                    scrollY = scrollY,
                    atStart = atStart,
                    atEnd = atEnd,
                    currentPage = latestCurrentPage,
                    pageCount = latestPageCount,
                    enabled = latestEnabled,
                )
                if (boundaryDirection != null) {
                    wheelEventHandledAtBoundary = true
                    latestOnIndicatorStateChange(
                        gestureTracker.pull(
                            direction = boundaryDirection,
                            distance = scrollY,
                        )
                    )
                }
                if (boundaryDirection != null || hadActiveGesture) {
                    scheduleWheelRelease()
                }
                wheelEventHandledAtBoundary
            },
            onWheelEventFinished = {
                wheelEventInProgress = false
                wheelEventHandledAtBoundary = false
            },
        )
    }
}

@Composable
internal fun EditorPageTurnIndicator(
    state: EditorPageTurnIndicatorState,
    modifier: Modifier = Modifier,
) {
    val message = editorPageTurnIndicatorMessage(state)
    if (state.direction == null || message.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                if (state.phase != EditorPageTurnPhase.Pulling) {
                    liveRegion = LiveRegionMode.Polite
                }
                contentDescription = message
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.phase == EditorPageTurnPhase.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

internal fun usesEditorSupportingPane(windowSizeClass: WindowSizeClass): Boolean = when (windowSizeClass) {
    WindowSizeClass.Expanded,
    WindowSizeClass.Large,
    WindowSizeClass.ExtraLarge -> true

    WindowSizeClass.Compact,
    WindowSizeClass.Medium -> false
}

internal fun editorSearchPresentation(windowSizeClass: WindowSizeClass): EditorSearchPresentation =
    if (usesEditorSupportingPane(windowSizeClass)) {
        EditorSearchPresentation.SupportingPane
    } else {
        EditorSearchPresentation.Dialog
    }

internal data class EditorSearchMatch(
    val start: Int,
    val length: Int,
)

class FileEditorScreen(
    private val file: FileSimpleInfo,
    private val canWrite: Boolean,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val fileState = koinInject<FileState>()
        val settings = koinInject<Settings>()
        var document by remember(file, canWrite) { mutableStateOf<FileEditorDocument?>(null) }
        var openingError by remember(file, canWrite) { mutableStateOf<String?>(null) }
        var openAttempt by remember(file, canWrite) { mutableIntStateOf(0) }

        LaunchedEffect(file, canWrite, openAttempt) {
            document = null
            openingError = null
            fileState.openEditorContent(file, canWrite)
                .onSuccess { source ->
                    document = FileEditorDocument(
                        source = source,
                        pageSize = EDITOR_PAGE_SIZE,
                        maxCachedPages = MAX_CACHED_EDITOR_PAGES,
                        lineIndexCache = ApplicationPrivateEditorLineIndexCache(),
                        searchHistoryStore = EditorSearchHistoryStore(settings),
                        backupStore = ApplicationPrivateEditorBackupStore(),
                        editorSessionKey = "${file.protocol}:${file.protocolId}:${file.path}",
                        recoveryJournalStore = ApplicationPrivateEditorRecoveryJournalStore(),
                    )
                }
                .onFailure { error ->
                    openingError = error.message ?: AppStrings.ui_unable_open_file
                }
        }

        val currentDocument = document
        if (currentDocument == null) {
            FileEditorOpeningScreen(
                fileName = file.name,
                error = openingError,
                onBack = navigator::pop,
                onRetry = { openAttempt++ },
            )
            return
        }

        FileEditorDocumentScreen(
            file = file,
            document = currentDocument,
            settings = settings,
            onBack = navigator::pop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorOpeningScreen(
    fileName: String,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppStrings.ui_return,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (error == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = AppStrings.ui_opening_file
                    },
                ) {
                    CircularProgressIndicator()
                    Text(AppStrings.editor_opening_file)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(AppStrings.ui_try_again)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorDocumentScreen(
    file: FileSimpleInfo,
    document: FileEditorDocument,
    settings: Settings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by document.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var localError by remember(document) { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember(document) { mutableStateOf(false) }
    var showEnableEditingDialog by remember(document) { mutableStateOf(false) }
    var showFileInformationDialog by remember(document) { mutableStateOf(false) }
    var showStatisticsDialog by remember(document) { mutableStateOf(false) }
    var savePreview by remember(document) { mutableStateOf<EditorSavePreview?>(null) }
    var saveSuccessAction by remember(document) { mutableStateOf<(() -> Unit)?>(null) }
    val presentationState = remember(document, settings) {
        FileEditorPresentationState(
            readBoolean = settings::getBoolean,
            writeBoolean = settings::putBoolean,
        )
    }

    fun reportFailure(result: Result<Unit>) {
        result.exceptionOrNull()?.let { error ->
            localError = error.message ?: AppStrings.ui_operation_failed
        }
    }

    LaunchedEffect(document) {
        try {
            document.initialize().also(::reportFailure)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                document.close()
            }
        }
    }

    fun persistSave(forceOverwriteConfirmed: Boolean = false) {
        scope.launch {
            localError = null
            savePreview = null
            document.save(forceOverwriteConfirmed)
                .onSuccess {
                    snackbarHostState.showLatestSnackbar(AppStrings.ui_file_saved)
                    saveSuccessAction?.invoke()
                    saveSuccessAction = null
                }
                .also(::reportFailure)
        }
    }

    fun requestSave(onSuccess: (() -> Unit)? = null) {
        scope.launch {
            localError = null
            saveSuccessAction = onSuccess
            val result = document.buildSavePreview()
            val preview = result.getOrNull()
            if (preview != null) {
                savePreview = preview
            } else if (!state.dirty) {
                snackbarHostState.showLatestSnackbar(AppStrings.ui_there_currently_no_pending_changes_save)
            } else {
                localError = result.exceptionOrNull()?.message ?: AppStrings.ui_unable_generate_save_preview
            }
        }
    }

    fun requestBack() {
        if (state.isDirty && state.canWrite) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    fun requestPage(pageIndex: Long) {
        scope.launch {
            localError = null
            document.goToPage(pageIndex).also(::reportFailure)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
        val editorBusy = state.isLoading || state.isSaving

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                FileEditorTopAppBar(
                    fileName = file.name,
                    state = state,
                    presentationState = presentationState,
                    windowSizeClass = windowSizeClass,
                    onBack = ::requestBack,
                    onShowStatistics = { showStatisticsDialog = true },
                    onShowInformation = { showFileInformationDialog = true },
                    onRequestEditing = { showEnableEditingDialog = true },
                )
            },
            bottomBar = {
                if (shouldShowFileEditorBottomBar(state.canWrite, state.pageCount)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp,
                    ) {
                        FileEditorBottomBar(
                            currentPage = state.pageIndex,
                            pageCount = state.pageCount,
                            enabled = !editorBusy,
                            showEditingActions = state.canWrite,
                            showPageNavigation = shouldShowFileEditorPageNavigation(state.pageCount),
                            canUndo = state.canUndo,
                            canRedo = state.canRedo,
                            isSaving = state.isSaving,
                            saveEnabled = state.canWrite && state.isDirty && !editorBusy,
                            onUndo = {
                                scope.launch { document.undo().also(::reportFailure) }
                            },
                            onRedo = {
                                scope.launch { document.redo().also(::reportFailure) }
                            },
                            onSave = { requestSave() },
                            onPreviousPage = { requestPage(state.pageIndex - 1L) },
                            onNextPage = { requestPage(state.pageIndex + 1L) },
                            onPageSelected = { pageIndex ->
                                scope.launch {
                                    localError = null
                                    document.goToPage(pageIndex)
                                        .onSuccess {
                                            snackbarHostState.showLatestSnackbar(AppStrings.ui_jumped_page_arg0.format(arg0 = (pageIndex + 1L).toString()))
                                        }
                                        .also(::reportFailure)
                                }
                            },
                            onLineSelected = { lineNumber ->
                                scope.launch {
                                    localError = null
                                    document.goToLine(lineNumber)
                                        .onSuccess {
                                            snackbarHostState.showLatestSnackbar(AppStrings.ui_jumped_line_arg0.format(arg0 = (lineNumber).toString()))
                                        }
                                        .also(::reportFailure)
                                }
                            },
                            onByteOffsetSelected = { offset ->
                                scope.launch {
                                    localError = null
                                    document.goToByteOffset(offset)
                                        .onSuccess {
                                            snackbarHostState.showLatestSnackbar(AppStrings.ui_jumped_byte_offset_arg0.format(arg0 = (offset).toString()))
                                        }
                                        .also(::reportFailure)
                                }
                            },
                            onPercentageSelected = { percentage ->
                                scope.launch {
                                    localError = null
                                    document.goToPercentage(percentage)
                                        .onSuccess {
                                            snackbarHostState.showLatestSnackbar(AppStrings.ui_jumped_arg0.format(arg0 = (percentage).toString()))
                                        }
                                        .also(::reportFailure)
                                }
                            },
                            currentLineNumber = state.currentLineNumber,
                            lineNumberProvisional = state.lineNumberProvisional,
                        )
                    }
                }
            },
        ) { paddingValues ->
            FileEditorContent(
                state = state,
                presentationState = presentationState,
                textSelection = document.selectedTextRange(),
                error = localError ?: state.error,
                onEncodingChange = { encoding ->
                    scope.launch {
                        localError = null
                        document.setEncoding(encoding).also(::reportFailure)
                    }
                },
                onNewlineConversionChange = { newline ->
                    localError = null
                    document.setNewlineConversion(newline).also(::reportFailure)
                },
                onTextChange = { text ->
                    localError = null
                    document.updateText(text).also(::reportFailure).isSuccess
                },
                onTextSelectionChange = { start, end ->
                    document.updateTextSelection(start, end).also(::reportFailure)
                },
                onPreviousPage = { requestPage(state.pageIndex - 1L) },
                onNextPage = { requestPage(state.pageIndex + 1L) },
                onSearch = { request ->
                    scope.launch {
                        localError = null
                        document.startSearch(request).also(::reportFailure)
                    }
                },
                onCancelSearch = {
                    scope.launch { document.cancelSearch() }
                },
                onDeleteSearchHistory = { entry ->
                    scope.launch {
                        localError = null
                        document.deleteSearchHistory(entry).also(::reportFailure)
                    }
                },
                onClearSearchHistory = {
                    scope.launch {
                        localError = null
                        document.clearSearchHistory()
                            .onSuccess { snackbarHostState.showLatestSnackbar(AppStrings.ui_recent_searches_cleared) }
                            .also(::reportFailure)
                    }
                },
                onSearchResultSelected = { result ->
                    scope.launch {
                        localError = null
                        document.goToSearchResult(result).also(::reportFailure)
                    }
                },
                onReplaceCurrent = { request, result ->
                    scope.launch {
                        localError = null
                        document.replaceCurrent(request, result)
                            .onSuccess { summary ->
                                snackbarHostState.showLatestSnackbar(AppStrings.ui_arg0_items_replaced.format(arg0 = (summary.replacedCount).toString()))
                            }
                            .onFailure { localError = it.message ?: AppStrings.ui_replacement_failed }
                    }
                },
                onReplaceAll = { request ->
                    scope.launch {
                        localError = null
                        document.replaceAll(request)
                            .onSuccess { summary ->
                                val skipped = summary.skippedOverlappingCount.takeIf { it > 0 }
                                    ?.let { AppStrings.ui_skipping_arg0_overlapping_items.format(arg0 = (it).toString()) }
                                    .orEmpty()
                                snackbarHostState.showLatestSnackbar(
                                    AppStrings.ui_arg0_item_arg1_replaced.format(arg0 = (summary.replacedCount).toString(), arg1 = skipped)
                                )
                            }
                            .onFailure { localError = it.message ?: AppStrings.ui_all_replacement_failed }
                    }
                },
                onRetry = {
                    if (state.isDirty && state.canWrite) {
                        requestSave()
                    } else {
                        scope.launch {
                            localError = null
                            val result =
                                if (state.pageCount <= 0L) {
                                    document.initialize()
                                } else {
                                    document.goToPage(state.pageIndex)
                                }
                            result.also(::reportFailure)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }

    if (showDiscardDialog) {
        EditorDialog(
            title = AppStrings.ui_there_unsaved_changes,
            onDismissRequest = { showDiscardDialog = false },
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_continue_editing,
                    onClick = { showDiscardDialog = false },
                )
                EditorDialogAction(
                    text = AppStrings.ui_discard_changes,
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                )
                EditorDialogAction(
                    text = AppStrings.ui_save_return,
                    onClick = {
                        showDiscardDialog = false
                        requestSave(onSuccess = onBack)
                    },
                )
            },
        ) {
            Text(
                AppStrings.ui_arg0_has_arg1_unsaved_changes_there_no_way_recover.format(arg0 = file.name, arg1 = (state.modifications.size).toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showEnableEditingDialog) {
        EditorDialog(
            title = AppStrings.ui_enable_edit_mode,
            onDismissRequest = { showEnableEditingDialog = false },
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_keep_read_only,
                    onClick = { showEnableEditingDialog = false },
                )
                EditorDialogAction(
                    text = AppStrings.ui_enable_editing,
                    onClick = {
                        showEnableEditingDialog = false
                        localError = null
                        document.unlockEditing(confirmed = true).also(::reportFailure)
                    },
                )
            },
        ) {
            if (state.isOversized) {
                EditorDialogSection(
                    title = AppStrings.ui_large_file_editing_restrictions,
                    tone = EditorDialogSectionTone.Warning,
                ) {
                    Text(
                        AppStrings.ui_file_must_least_1_gib_control_memory_write_risks,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(AppStrings.ui_support_small_range_text_replacement, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    AppStrings.ui_modifications_may_overwrite_original_content_but_you_can_still,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showFileInformationDialog) {
        EditorFileInformationDialog(
            file = file,
            state = state,
            onDismiss = { showFileInformationDialog = false },
        )
    }

    if (showStatisticsDialog) {
        EditorStatisticsDialog(
            state = state,
            onStart = { request ->
                scope.launch {
                    localError = null
                    document.startStatistics(request).also(::reportFailure)
                }
            },
            onCancel = { scope.launch { document.cancelStatistics() } },
            onDismiss = { showStatisticsDialog = false },
        )
    }

    savePreview?.let { preview ->
        EditorDialog(
            title = AppStrings.ui_preview_before_saving,
            onDismissRequest = {
                savePreview = null
                saveSuccessAction = null
            },
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_cancel,
                    onClick = {
                        savePreview = null
                        saveSuccessAction = null
                    },
                )
                EditorDialogAction(
                    text = AppStrings.ui_confirm_save,
                    onClick = { persistSave() },
                    enabled = preview.hasEnoughBackupSpace,
                )
            },
        ) {
            EditorDialogSection(title = AppStrings.ui_save_results) {
                EditorDialogPropertyRow(
                    AppStrings.ui_file_size,
                    "${preview.originalSize.editorByteCountLabel()} → ${preview.newSize.editorByteCountLabel()}",
                )
                EditorDialogPropertyRow(AppStrings.ui_size_changes, preview.sizeDelta.signedByteDeltaLabel())
                EditorDialogPropertyRow(AppStrings.ui_text_encoding, preview.encoding.displayName())
                EditorDialogPropertyRow(
                    AppStrings.ui_newline_conversion,
                    preview.newlineConversion?.displayName() ?: AppStrings.ui_stay,
                )
            }
            EditorDialogSection(
                title = AppStrings.ui_backup,
                tone = if (preview.hasEnoughBackupSpace) {
                    EditorDialogSectionTone.Neutral
                } else {
                    EditorDialogSectionTone.Destructive
                },
            ) {
                EditorDialogPropertyRow(AppStrings.ui_backup_method, preview.backupMode.displayName())
                EditorDialogPropertyRow(AppStrings.ui_estimated_occupancy, preview.estimatedBackupBytes.editorByteCountLabel())
                preview.availableBackupBytes?.let { available ->
                    EditorDialogPropertyRow(AppStrings.ui_available_space, available.editorByteCountLabel())
                }
                if (!preview.hasEnoughBackupSpace) {
                    Text(
                        AppStrings.ui_insufficient_backup_space_please_free_up_space_adjust_backup,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (preview.replacementCharacterCount > 0) {
                EditorDialogSection(
                    title = AppStrings.ui_coding_risks,
                    tone = EditorDialogSectionTone.Destructive,
                ) {
                    Text(
                        AppStrings.ui_there_arg0_characters_that_cannot_saved_current_encoding_will.format(arg0 = (preview.replacementCharacterCount).toString()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    preview.replacementCharacterSamples.forEach { sample ->
                        Text(
                            AppStrings.ui_page_arg0_character_arg1.format(arg0 = (sample.pageIndex + 1).toString(), arg1 = (sample.characterIndex).toString()) +
                                    sample.character.toVisibleStatisticLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            EditorDialogSection(title = AppStrings.ui_change_scope_arg0.format(arg0 = (preview.changedRanges.size).toString())) {
                if (preview.changedRanges.isEmpty()) {
                    Text(AppStrings.ui_there_no_writable_byte_changes)
                } else {
                    preview.changedRanges.forEachIndexed { index, change ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            text = change.byteRangeLabel(),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            text = AppStrings.ui_arg0_arg1_bytes.format(arg0 = (change.originalByteCount).toString(), arg1 = (change.replacementByteCount).toString()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    state.saveConflict?.let { conflict ->
        EditorDialog(
            title = AppStrings.ui_file_has_been_modified_externally,
            onDismissRequest = document::dismissSaveConflict,
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_keep_current_edits,
                    onClick = document::dismissSaveConflict,
                )
                EditorDialogAction(
                    text = AppStrings.ui_reload,
                    onClick = {
                        scope.launch {
                            document.reload(discardChangesConfirmed = true).also(::reportFailure)
                        }
                    },
                )
                EditorDialogAction(
                    text = AppStrings.ui_force_coverage,
                    onClick = { persistSave(forceOverwriteConfirmed = true) },
                )
            },
        ) {
            EditorDialogSection(title = AppStrings.ui_version_information) {
                EditorDialogPropertyRow(
                    AppStrings.ui_open_version,
                    (conflict.expected.revision ?: conflict.expected.updatedAt ?: AppStrings.ui_unknown).toString(),
                    monospace = true,
                )
                EditorDialogPropertyRow(
                    AppStrings.ui_disk_current_version,
                    (conflict.actual.revision ?: conflict.actual.updatedAt ?: AppStrings.ui_unknown).toString(),
                    monospace = true,
                )
            }
            EditorDialogSection(
                title = AppStrings.ui_covering_risks,
                tone = EditorDialogSectionTone.Destructive,
            ) {
                Text(
                    AppStrings.ui_forced_overwriting_permanently_replaces_version_written_external_program_keeps,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (state.recoveryAvailable) {
        EditorDialog(
            title = AppStrings.ui_unfinished_edits_found,
            onDismissRequest = {},
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_discard_recovery_records,
                    onClick = { scope.launch { document.discardRecovery().also(::reportFailure) } },
                )
                EditorDialogAction(
                    text = if (state.recoverySourceChanged) AppStrings.ui_restore_review else AppStrings.ui_revert_edit,
                    onClick = {
                        scope.launch {
                            document.restoreRecovery(
                                allowChangedSourceForReview = state.recoverySourceChanged,
                            ).also(::reportFailure)
                        }
                    },
                )
            },
        ) {
            if (state.recoverySourceChanged) {
                EditorDialogSection(
                    title = AppStrings.ui_source_file_has_changed,
                    tone = EditorDialogSectionTone.Destructive,
                ) {
                    Text(
                        AppStrings.ui_modifications_can_restored_review_saved_but_changed_source_files,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    AppStrings.ui_last_editing_session_ended_abnormally_you_can_restore_changes,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorTopAppBar(
    fileName: String,
    state: FileEditorDocumentState,
    presentationState: FileEditorPresentationState,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onShowStatistics: () -> Unit,
    onShowInformation: () -> Unit,
    onRequestEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionEnabled = !state.isLoading && !state.isSaving
    val documentStatusText = when {
        state.recoveryReviewOnly -> AppStrings.ui_resume_review_disable_overwriting
        state.canWrite && state.isDirty -> AppStrings.ui_there_unsaved_changes
        state.canWrite -> AppStrings.ui_saved
        state.sourceCanWrite -> AppStrings.ui_read_only_editing_enabled
        else -> AppStrings.ui_read_only
    }
    val statusText = AppStrings.ui_arg0_text.format(arg0 = documentStatusText)
    val statusColor = when {
        state.recoveryReviewOnly -> MaterialTheme.colorScheme.error
        state.canWrite && state.isDirty -> MaterialTheme.colorScheme.tertiary
        state.canWrite -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = AppStrings.ui_return,
                )
            }
        },
        actions = {
            if (!state.canWrite && state.sourceCanWrite) {
                if (windowSizeClass == WindowSizeClass.Compact) {
                    IconButton(
                        onClick = onRequestEditing,
                        enabled = interactionEnabled,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = AppStrings.ui_enable_editing)
                    }
                } else {
                    TextButton(
                        onClick = onRequestEditing,
                        enabled = interactionEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(AppStrings.ui_enable_editing)
                    }
                }
            }
            IconButton(
                onClick = presentationState::toggleSearch,
            ) {
                Icon(
                    imageVector = if (presentationState.searchExpanded) {
                        Icons.Default.Close
                    } else {
                        Icons.Default.Search
                    },
                    contentDescription = if (presentationState.searchExpanded) {
                        AppStrings.ui_turn_off_file_search
                    } else {
                        AppStrings.ui_search_files
                    },
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_editor_operations)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    Text(
                        text = AppStrings.ui_display_format,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(AppStrings.ui_encoding_arg0.format(arg0 = state.encoding.displayName())) },
                        onClick = {
                            menuExpanded = false
                            presentationState.showEncodingDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            val current = state.newlineKind.displayName()
                            val conversion = state.newlineConversion
                                ?.let { " → ${it.displayName()}" }
                                .orEmpty()
                            Text(AppStrings.ui_line_break_arg0_arg1.format(arg0 = current, arg1 = conversion))
                        },
                        onClick = {
                            menuExpanded = false
                            presentationState.showNewlineDialog = true
                        },
                    )
                    EditorToggleMenuItem(
                        text = AppStrings.ui_show_line_number,
                        checked = presentationState.showLineNumbers,
                        onClick = presentationState::toggleShowLineNumbers,
                    )
                    EditorToggleMenuItem(
                        text = AppStrings.ui_automatic_line_wrapping,
                        checked = presentationState.automaticWrap,
                        onClick = presentationState::toggleAutomaticWrap,
                    )
                    EditorToggleMenuItem(
                        text = AppStrings.ui_show_invisible_characters,
                        checked = presentationState.showInvisibleCharacters,
                        onClick = {
                            presentationState.showInvisibleCharacters =
                                !presentationState.showInvisibleCharacters
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(AppStrings.ui_file_statistics) },
                        onClick = {
                            menuExpanded = false
                            onShowStatistics()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(AppStrings.ui_file_information) },
                        onClick = {
                            menuExpanded = false
                            onShowInformation()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun EditorToggleMenuItem(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = if (checked) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                )
            }
        } else {
            null
        },
    )
}

private fun EditorStatisticsScope.displayName(): String = when (this) {
    EditorStatisticsScope.CurrentPage -> AppStrings.ui_current_page
    EditorStatisticsScope.Selection -> AppStrings.ui_constituency
    EditorStatisticsScope.WholeFile -> AppStrings.ui_entire_file
}

@Composable
private fun EditorFileInformationDialog(
    file: FileSimpleInfo,
    state: FileEditorDocumentState,
    onDismiss: () -> Unit,
) {
    val information = editorFileInformation(file, state)
    EditorDialog(
        title = AppStrings.ui_file_information,
        onDismissRequest = onDismiss,
        actions = {
            EditorDialogAction(
                text = AppStrings.ui_close,
                onClick = onDismiss,
            )
        },
    ) {
        EditorDialogSection(title = AppStrings.ui_file) {
            EditorDialogPropertyRow(AppStrings.ui_path, information.protocolQualifiedPath, monospace = true)
            EditorDialogPropertyRow(AppStrings.ui_size, information.size.editorByteCountLabel())
            EditorDialogPropertyRow(AppStrings.ui_creation_time, information.createdAt.timestampToSyncDate())
            EditorDialogPropertyRow(AppStrings.ui_modification_time, information.updatedAt.timestampToSyncDate())
        }
        EditorDialogSection(title = AppStrings.ui_text_parsing) {
            EditorDialogPropertyRow(AppStrings.ui_encoding, information.encoding.displayName())
            EditorDialogPropertyRow(AppStrings.ui_newline_character, information.newline.displayName())
        }
        EditorDialogSection(title = AppStrings.ui_current_location) {
            EditorDialogPropertyRow(
                AppStrings.ui_byte_offset,
                information.currentOffset?.let { "$it · 0x${it.toString(16).uppercase()}" } ?: AppStrings.ui_unknown,
                monospace = true,
            )
            EditorDialogPropertyRow(AppStrings.ui_line_number, information.currentLine?.toString() ?: AppStrings.ui_indexing)
            EditorDialogPropertyRow(AppStrings.ui_selection_size, information.selectionSize.editorByteCountLabel())
        }
    }
}

@Composable
private fun EditorStatisticsDialog(
    state: FileEditorDocumentState,
    onStart: (EditorStatisticsRequest) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var keywordInput by remember { mutableStateOf("") }
    var statisticsScope by remember { mutableStateOf(EditorStatisticsScope.WholeFile) }
    val selectionStart = state.selectionStartOffset
    val selectionEnd = state.selectionEndOffsetExclusive
    val selectionAvailable = selectionStart != null && selectionEnd != null && selectionEnd > selectionStart
    val statistics = state.statistics.takeIf { state.statisticsScope == statisticsScope }
    val keywordStatistics = state.keywordStatistics.takeIf { state.statisticsScope == statisticsScope }

    LaunchedEffect(selectionAvailable) {
        if (!selectionAvailable && statisticsScope == EditorStatisticsScope.Selection) {
            statisticsScope = EditorStatisticsScope.CurrentPage
        }
    }
    EditorDialog(
        title = AppStrings.ui_file_statistics,
        onDismissRequest = { if (!state.isAnalyzing) onDismiss() },
        actions = {
            EditorDialogAction(
                text = AppStrings.ui_close,
                onClick = onDismiss,
                enabled = !state.isAnalyzing,
            )
            if (state.isAnalyzing) {
                EditorDialogAction(
                    text = AppStrings.ui_stop_statistics,
                    onClick = onCancel,
                )
            } else {
                EditorDialogAction(
                    text = if (state.analysisCompleted) AppStrings.ui_recount else AppStrings.ui_start_statistics,
                    onClick = {
                        onStart(
                            EditorStatisticsRequest(
                                scope = statisticsScope,
                                keywordQueries = keywordInput.lineSequence().map(String::trim)
                                    .filter(String::isNotEmpty).distinct().toList(),
                            )
                        )
                    },
                    enabled = statisticsScope != EditorStatisticsScope.Selection || selectionAvailable,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = AppStrings.ui_statistical_range,
                style = MaterialTheme.typography.titleSmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EditorStatisticsScope.entries.forEachIndexed { index, scope ->
                        SegmentedButton(
                            selected = statisticsScope == scope,
                            onClick = { statisticsScope = scope },
                            enabled = !state.isAnalyzing &&
                                (scope != EditorStatisticsScope.Selection || selectionAvailable),
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                EditorStatisticsScope.entries.size,
                            ),
                            label = {
                                Text(
                                    when (scope) {
                                        EditorStatisticsScope.CurrentPage -> AppStrings.ui_current_page
                                        EditorStatisticsScope.Selection -> AppStrings.ui_constituency
                                        EditorStatisticsScope.WholeFile -> AppStrings.ui_entire_file
                                    }
                                )
                            },
                        )
                    }
                }
                if (!selectionAvailable) {
                    Text(
                        AppStrings.ui_select_text_bytes_count_selection,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = keywordInput,
                    onValueChange = { keywordInput = it.take(MAX_SEARCH_QUERY_LENGTH) },
                    label = { Text(AppStrings.ui_keywords_one_per_line_optional) },
                    enabled = !state.isAnalyzing,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.isAnalyzing) {
            EditorDialogSection(title = AppStrings.ui_analyze_progress, tone = EditorDialogSectionTone.Accent) {
                LinearProgressIndicator(
                    progress = { state.analysisProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = AppStrings.ui_statistical_progress_arg0.format(arg0 = ((state.analysisProgress * 100).toInt()).toString())
                    },
                )
                Text(
                    if (statistics == null) {
                        AppStrings.ui_reading_file_arg0.format(arg0 = ((state.analysisProgress * 100).toInt()).toString())
                    } else {
                        AppStrings.ui_basic_statistics_have_been_completed_keywords_being_counted
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.analysisError?.let { error ->
            EditorDialogSection(title = AppStrings.ui_statistics_failed, tone = EditorDialogSectionTone.Destructive) {
                Text(error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        statistics?.let { result ->
            val frequent = result.characterFrequency.entries
                .sortedByDescending { it.value }
                .take(8)
                .joinToString { (char, count) -> "${char.toVisibleStatisticLabel()}: $count" }
            val byteKinds = result.byteDistribution.count { it > 0L }
            EditorDialogSection(
                title = AppStrings.ui_basic_statistics_arg0.format(arg0 = statisticsScope.displayName()),
            ) {
                EditorDialogPropertyRow(AppStrings.ui_number_lines, result.lineCount.toString())
                EditorDialogPropertyRow(AppStrings.ui_blank_line, result.blankLineCount.toString())
                EditorDialogPropertyRow(AppStrings.ui_0x00_bytes, result.zeroByteCount.toString())
                EditorDialogPropertyRow(AppStrings.ui_replace_character, result.replacementCharacterCount.toString())
                EditorDialogPropertyRow(AppStrings.ui_high_frequency_characters, frequent.ifBlank { AppStrings.ui_none })
                EditorDialogPropertyRow(AppStrings.ui_byte_value_that_appears, "$byteKinds / 256")
            }
        }
        keywordStatistics?.occurrences?.takeIf { it.isNotEmpty() }?.let { occurrences ->
            EditorDialogSection(title = AppStrings.ui_keyword_statistics) {
                occurrences.entries.forEachIndexed { index, (keyword, count) ->
                    if (index > 0) HorizontalDivider()
                    Text(
                        editorSearchQueryLabel(keyword),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        AppStrings.ui_arg0_times.format(arg0 = (count).toString()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private fun Char.toVisibleStatisticLabel(): String = when (this) {
    ' ' -> AppStrings.editor_whitespace_space
    '\t' -> "Tab"
    '\r' -> "CR"
    '\n' -> "LF"
    else -> toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileEditorContent(
    state: FileEditorDocumentState,
    presentationState: FileEditorPresentationState,
    textSelection: IntRange?,
    error: String?,
    onEncodingChange: (EditorTextEncoding) -> Unit,
    onNewlineConversionChange: (EditorNewlineKind?) -> Unit,
    onTextChange: (String) -> Boolean,
    onTextSelectionChange: (Int, Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSearch: (EditorSearchRequest) -> Unit,
    onCancelSearch: () -> Unit,
    onDeleteSearchHistory: (EditorSearchHistoryEntry) -> Unit,
    onClearSearchHistory: () -> Unit,
    onSearchResultSelected: (EditorSearchResult) -> Unit,
    onReplaceCurrent: (EditorReplaceRequest, EditorSearchResult) -> Unit,
    onReplaceAll: (EditorReplaceRequest) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionEnabled = !state.isLoading && !state.isSaving
    val searchExpanded = presentationState.searchExpanded
    val showEncodingDialog = presentationState.showEncodingDialog
    val showNewlineDialog = presentationState.showNewlineDialog
    val showLineNumbers = presentationState.showLineNumbers
    val automaticWrap = presentationState.automaticWrap
    val showInvisibleCharacters = presentationState.showInvisibleCharacters
    var searchQuery by remember { mutableStateOf("") }
    var replacementText by remember { mutableStateOf("") }
    var activeMatchIndex by remember { mutableIntStateOf(0) }
    var searchMode by remember { mutableStateOf(EditorSearchMode.Text) }
    var searchScope by remember { mutableStateOf(EditorSearchScope.WholeFile) }
    var searchCaseSensitive by remember { mutableStateOf(true) }
    var searchWholeWord by remember { mutableStateOf(false) }
    var searchDirection by remember { mutableStateOf(EditorSearchDirection.Forward) }
    var pendingPageLanding by remember { mutableStateOf<EditorPendingPageLanding?>(null) }
    var pageTurnIndicatorState by remember { mutableStateOf(EditorPageTurnIndicatorState()) }
    var pageTurnTargetPage by remember { mutableStateOf<Long?>(null) }
    val visibleSearchHistory = state.searchHistory
    val activeSearchResult = state.searchResults.getOrNull(activeMatchIndex)
    val activeMatch = activeSearchResult?.takeIf {
        it.startOffset >= state.pageStartOffset &&
                it.endOffsetExclusive <= state.pageEndOffsetExclusive
    }?.let { result ->
        EditorSearchMatch(
            start = (result.startOffset - state.pageStartOffset).toInt(),
            length = (result.endOffsetExclusive - result.startOffset).toInt(),
        )
    }

    LaunchedEffect(state.searchResults.size) {
        activeMatchIndex = activeMatchIndex.coerceIn(0, state.searchResults.lastIndex.coerceAtLeast(0))
    }
    val pageStartsAtEnd = pendingPageLanding?.let { pending ->
        pending.pageIndex == state.pageIndex && pending.atEnd
    } == true
    val verticalEditorScrollState = remember(state.pageIndex) {
        ScrollState(if (pageStartsAtEnd) Int.MAX_VALUE else 0)
    }
    LaunchedEffect(state.pageIndex) {
        pendingPageLanding = null
    }
    LaunchedEffect(state.pageIndex, state.isLoading, pageTurnTargetPage) {
        if (
            pageTurnLoadingFinished(
                targetPage = pageTurnTargetPage,
                currentPage = state.pageIndex,
                isLoading = state.isLoading,
            )
        ) {
            pageTurnIndicatorState = EditorPageTurnIndicatorState()
            pageTurnTargetPage = null
        }
    }
    LaunchedEffect(interactionEnabled) {
        if (!interactionEnabled && pageTurnIndicatorState.phase != EditorPageTurnPhase.Loading) {
            pageTurnIndicatorState = EditorPageTurnIndicatorState()
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
        val searchPresentation = editorSearchPresentation(windowSizeClass)
        val showSearchAsSupportingPane =
            searchExpanded && searchPresentation == EditorSearchPresentation.SupportingPane
        val showSearchAsDialog =
            searchExpanded && searchPresentation == EditorSearchPresentation.Dialog
        val pageTurnScrollHandler = rememberEditorPageTurnScrollHandler(
            currentPage = state.pageIndex,
            pageCount = state.pageCount,
            enabled = interactionEnabled,
            onIndicatorStateChange = { pageTurnIndicatorState = it },
            onPreviousPage = {
                val targetPage = state.pageIndex - 1L
                pageTurnTargetPage = targetPage
                pendingPageLanding = EditorPendingPageLanding(
                    pageIndex = targetPage,
                    atEnd = true,
                )
                onPreviousPage()
            },
            onNextPage = {
                val targetPage = state.pageIndex + 1L
                pageTurnTargetPage = targetPage
                pendingPageLanding = EditorPendingPageLanding(
                    pageIndex = targetPage,
                    atEnd = false,
                )
                onNextPage()
            },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isSaving) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = AppStrings.ui_saving_progress_arg0.format(arg0 = ((state.saveProgress * 100).toInt()).toString())
                        },
                ) {
                    LinearProgressIndicator(
                        progress = { state.saveProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = AppStrings.ui_saving_arg0.format(arg0 = ((state.saveProgress.coerceIn(0f, 1f) * 100).toInt()).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (state.isLineIndexing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription =
                                AppStrings.ui_building_line_number_index_progress_arg0.format(arg0 = ((state.lineIndexProgress * 100).toInt()).toString())
                        },
                ) {
                    LinearProgressIndicator(
                        progress = { state.lineIndexProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = AppStrings.ui_building_line_number_index_arg0.format(arg0 = ((state.lineIndexProgress.coerceIn(0f, 1f) * 100).toInt()).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry, enabled = interactionEnabled) {
                            Text(AppStrings.ui_try_again)
                        }
                    }
                }
            }

            if (state.encodingConfidence == EditorEncodingConfidence.Low) {
                Text(
                    text = AppStrings.ui_coding_recognition_confidence_low_please_confirm_coding_before_editing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .nestedScroll(pageTurnScrollHandler.nestedScrollConnection)
                            .editorPageTurnWheelScroll(
                                scrollState = verticalEditorScrollState,
                                handler = pageTurnScrollHandler,
                            ),
                    ) {
                        TextPageEditor(
                            text = state.text,
                            pageIndex = state.pageIndex,
                            verticalScrollState = verticalEditorScrollState,
                            readOnly = !state.canWrite || !interactionEnabled,
                            searchMatch = activeMatch,
                            showLineNumbers = showLineNumbers,
                            automaticWrap = automaticWrap,
                            showInvisibleCharacters = showInvisibleCharacters,
                            selection = textSelection,
                            onTextChange = onTextChange,
                            onSelectionChange = onTextSelectionChange,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (state.isLoading) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.semantics {
                                            liveRegion = LiveRegionMode.Polite
                                            contentDescription = AppStrings.ui_reading_file_pages
                                        },
                                    ) {
                                        CircularProgressIndicator()
                                        Text(AppStrings.ui_reading_current_page)
                                    }
                                }
                            }
                        }

                        EditorPageTurnIndicator(
                            state = pageTurnIndicatorState,
                            modifier = Modifier
                                .align(
                                    when (pageTurnIndicatorState.direction) {
                                        EditorPageTurnDirection.Previous -> Alignment.TopCenter
                                        EditorPageTurnDirection.Next,
                                        null -> Alignment.BottomCenter
                                    }
                                )
                                .offset(
                                    y = when (pageTurnIndicatorState.direction) {
                                        EditorPageTurnDirection.Previous ->
                                            -EditorPageTurnThreshold * (1f - pageTurnIndicatorState.progress)

                                        EditorPageTurnDirection.Next ->
                                            EditorPageTurnThreshold * (1f - pageTurnIndicatorState.progress)

                                        null -> 0.dp
                                    }
                                ),
                        )
                    }

                }

                if (showSearchAsSupportingPane) {
                    VerticalDivider()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .width(384.dp)
                            .fillMaxHeight(),
                    ) {
                        AdvancedSearchPanel(
                            query = searchQuery,
                            replacement = replacementText,
                            mode = searchMode,
                            scope = searchScope,
                            caseSensitive = searchCaseSensitive,
                            wholeWord = searchWholeWord,
                            direction = searchDirection,
                            history = visibleSearchHistory,
                            activeMatchIndex = activeMatchIndex,
                            results = state.searchResults,
                            totalCount = state.searchTotalCount,
                            detailsTruncated = state.searchDetailsTruncated,
                            isSearching = state.isSearching,
                            progress = state.searchProgress,
                            error = state.searchError,
                            canReplace = state.canWrite && interactionEnabled,
                            onQueryChange = { searchQuery = it.take(MAX_SEARCH_QUERY_LENGTH) },
                            onReplacementChange = { replacementText = it.take(MAX_SEARCH_QUERY_LENGTH) },
                            onModeChange = { searchMode = it },
                            onScopeChange = { searchScope = it },
                            onCaseSensitiveChange = { searchCaseSensitive = it },
                            onWholeWordChange = { searchWholeWord = it },
                            onDirectionChange = { searchDirection = it },
                            onHistorySelected = { entry ->
                                searchQuery = entry.queries.joinToString("\n")
                                searchMode = entry.mode
                                searchCaseSensitive = entry.caseSensitive
                                searchWholeWord = entry.wholeWord
                                searchDirection = entry.direction
                            },
                            onSearch = {
                                buildEditorSearchRequest(
                                    query = searchQuery,
                                    mode = searchMode,
                                    scope = searchScope,
                                    state = state,
                                    caseSensitive = searchCaseSensitive,
                                    wholeWord = searchWholeWord,
                                    direction = searchDirection,
                                )?.let(onSearch)
                            },
                            onCancel = onCancelSearch,
                            onDeleteHistory = onDeleteSearchHistory,
                            onClearHistory = onClearSearchHistory,
                            onPrevious = {
                                if (state.searchResults.isNotEmpty()) {
                                    activeMatchIndex =
                                        (activeMatchIndex - 1 + state.searchResults.size) % state.searchResults.size
                                    onSearchResultSelected(state.searchResults[activeMatchIndex])
                                }
                            },
                            onNext = {
                                if (state.searchResults.isNotEmpty()) {
                                    activeMatchIndex = (activeMatchIndex + 1) % state.searchResults.size
                                    onSearchResultSelected(state.searchResults[activeMatchIndex])
                                }
                            },
                            onResultSelected = { index, result ->
                                activeMatchIndex = index
                                onSearchResultSelected(result)
                            },
                            onReplaceCurrent = {
                                val result = state.searchResults.getOrNull(activeMatchIndex)
                                val searchRequest = buildEditorSearchRequest(
                                    query = searchQuery,
                                    mode = searchMode,
                                    scope = searchScope,
                                    state = state,
                                    caseSensitive = searchCaseSensitive,
                                    wholeWord = searchWholeWord,
                                    direction = searchDirection,
                                )
                                if (result != null && searchRequest != null) {
                                    onReplaceCurrent(
                                        EditorReplaceRequest(searchRequest, replacementText),
                                        result,
                                    )
                                }
                            },
                            onReplaceAll = {
                                buildEditorSearchRequest(
                                    query = searchQuery,
                                    mode = searchMode,
                                    scope = searchScope,
                                    state = state,
                                    caseSensitive = searchCaseSensitive,
                                    wholeWord = searchWholeWord,
                                    direction = searchDirection,
                                )?.let { searchRequest ->
                                    onReplaceAll(EditorReplaceRequest(searchRequest, replacementText))
                                }
                            },
                            onDismiss = presentationState::dismissSearch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        if (showSearchAsDialog) {
            EditorDialog(
                title = AppStrings.ui_search_replace,
                onDismissRequest = presentationState::dismissSearch,
                contentScrollable = false,
                forceFullScreen = EDITOR_SEARCH_DIALOG_ALWAYS_FULL_SCREEN,
                fillScreenWidth = true,
                modifier = Modifier.fillMaxSize(),
                titleActions = {
                    FilledIconButton(
                        onClick = {
                            buildEditorSearchRequest(
                                query = searchQuery,
                                mode = searchMode,
                                scope = searchScope,
                                state = state,
                                caseSensitive = searchCaseSensitive,
                                wholeWord = searchWholeWord,
                                direction = searchDirection,
                            )?.let(onSearch)
                        },
                        enabled = !state.isSearching &&
                                parseEditorSearchQueries(searchQuery, searchMode).isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search)
                    }
                    IconButton(
                        onClick = {
                            if (state.isSearching) {
                                onCancelSearch()
                            } else {
                                presentationState.dismissSearch()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (state.isSearching) {
                                AppStrings.ui_cancel_search
                            } else {
                                AppStrings.ui_turn_off_search_replace
                            },
                        )
                    }
                },
            ) {
                AdvancedSearchPanel(
                    query = searchQuery,
                    replacement = replacementText,
                    mode = searchMode,
                    scope = searchScope,
                    caseSensitive = searchCaseSensitive,
                    wholeWord = searchWholeWord,
                    direction = searchDirection,
                    history = visibleSearchHistory,
                    activeMatchIndex = activeMatchIndex,
                    results = state.searchResults,
                    totalCount = state.searchTotalCount,
                    detailsTruncated = state.searchDetailsTruncated,
                    isSearching = state.isSearching,
                    progress = state.searchProgress,
                    error = state.searchError,
                    canReplace = state.canWrite && interactionEnabled,
                    onQueryChange = { searchQuery = it.take(MAX_SEARCH_QUERY_LENGTH) },
                    onReplacementChange = { replacementText = it.take(MAX_SEARCH_QUERY_LENGTH) },
                    onModeChange = { searchMode = it },
                    onScopeChange = { searchScope = it },
                    onCaseSensitiveChange = { searchCaseSensitive = it },
                    onWholeWordChange = { searchWholeWord = it },
                    onDirectionChange = { searchDirection = it },
                    onHistorySelected = { entry ->
                        searchQuery = entry.queries.joinToString("\n")
                        searchMode = entry.mode
                        searchCaseSensitive = entry.caseSensitive
                        searchWholeWord = entry.wholeWord
                        searchDirection = entry.direction
                    },
                    onSearch = {
                        buildEditorSearchRequest(
                            query = searchQuery,
                            mode = searchMode,
                            scope = searchScope,
                            state = state,
                            caseSensitive = searchCaseSensitive,
                            wholeWord = searchWholeWord,
                            direction = searchDirection,
                        )?.let(onSearch)
                    },
                    onCancel = onCancelSearch,
                    onDeleteHistory = onDeleteSearchHistory,
                    onClearHistory = onClearSearchHistory,
                    onPrevious = {
                        if (state.searchResults.isNotEmpty()) {
                            activeMatchIndex =
                                (activeMatchIndex - 1 + state.searchResults.size) % state.searchResults.size
                            onSearchResultSelected(state.searchResults[activeMatchIndex])
                        }
                    },
                    onNext = {
                        if (state.searchResults.isNotEmpty()) {
                            activeMatchIndex = (activeMatchIndex + 1) % state.searchResults.size
                            onSearchResultSelected(state.searchResults[activeMatchIndex])
                        }
                    },
                    onResultSelected = { index, result ->
                        activeMatchIndex = index
                        onSearchResultSelected(result)
                        presentationState.dismissSearch()
                    },
                    onReplaceCurrent = {
                        val result = state.searchResults.getOrNull(activeMatchIndex)
                        val searchRequest = buildEditorSearchRequest(
                            query = searchQuery,
                            mode = searchMode,
                            scope = searchScope,
                            state = state,
                            caseSensitive = searchCaseSensitive,
                            wholeWord = searchWholeWord,
                            direction = searchDirection,
                        )
                        if (result != null && searchRequest != null) {
                            onReplaceCurrent(
                                EditorReplaceRequest(searchRequest, replacementText),
                                result,
                            )
                        }
                    },
                    onReplaceAll = {
                        buildEditorSearchRequest(
                            query = searchQuery,
                            mode = searchMode,
                            scope = searchScope,
                            state = state,
                            caseSensitive = searchCaseSensitive,
                            wholeWord = searchWholeWord,
                            direction = searchDirection,
                        )?.let { searchRequest ->
                            onReplaceAll(EditorReplaceRequest(searchRequest, replacementText))
                        }
                    },
                    onDismiss = presentationState::dismissSearch,
                    showHeader = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showEncodingDialog) {
        ChoiceDialog(
            title = AppStrings.ui_select_text_encoding,
            options = EditorTextEncoding.entries.map { it to it.displayName() },
            selected = state.encoding,
            onSelected = { encoding ->
                presentationState.showEncodingDialog = false
                onEncodingChange(encoding)
            },
            onDismiss = { presentationState.showEncodingDialog = false },
        )
    }
    if (showNewlineDialog) {
        ChoiceDialog(
            title = AppStrings.ui_how_save_line_breaks,
            options = listOf(
                null to AppStrings.ui_keep_original_line_breaks,
                EditorNewlineKind.LF to AppStrings.ui_convert_lf,
                EditorNewlineKind.CRLF to AppStrings.ui_convert_crlf,
                EditorNewlineKind.CR to AppStrings.ui_convert_cr,
            ),
            selected = state.newlineConversion,
            optionDescription = { newline ->
                when (newline) {
                    null -> AppStrings.ui_no_conversion_retain_existing_line_breaks_file
                    EditorNewlineKind.LF -> "Unix / macOS（\\n）"
                    EditorNewlineKind.CRLF -> "Windows（\\r\\n）"
                    EditorNewlineKind.CR -> AppStrings.ui_older_macs_r
                    EditorNewlineKind.None,
                    EditorNewlineKind.Mixed -> null
                }
            },
            onSelected = { newline ->
                presentationState.showNewlineDialog = false
                onNewlineConversionChange(newline)
            },
            onDismiss = { presentationState.showNewlineDialog = false },
        )
    }
}

private fun buildEditorSearchRequest(
    query: String,
    mode: EditorSearchMode,
    scope: EditorSearchScope,
    state: FileEditorDocumentState,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    direction: EditorSearchDirection,
): EditorSearchRequest? {
    val queries = parseEditorSearchQueries(query, mode)
    if (queries.isEmpty()) return null

    val selectionStart = state.selectionStartOffset
    val selectionEnd = state.selectionEndOffsetExclusive
    val range = when (scope) {
        EditorSearchScope.CurrentPage -> EditorSearchRange(
            state.pageStartOffset,
            state.pageEndOffsetExclusive,
        )

        EditorSearchScope.Selection -> if (
            selectionStart != null && selectionEnd != null && selectionEnd > selectionStart
        ) {
            EditorSearchRange(selectionStart, selectionEnd)
        } else {
            EditorSearchRange(state.pageStartOffset, state.pageEndOffsetExclusive)
        }

        EditorSearchScope.WholeFile -> EditorSearchRange(0L, state.fileSize)
    }
    return EditorSearchRequest(
        mode = mode,
        queries = if (mode == EditorSearchMode.Regex) queries.take(1) else queries,
        range = range,
        encoding = state.encoding,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
        direction = direction,
    )
}

internal fun parseEditorSearchQueries(
    query: String,
    mode: EditorSearchMode,
): List<String> = when (mode) {
    EditorSearchMode.Text,
    EditorSearchMode.Regex -> query.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()
}

internal val editorSearchUiModes = listOf(
    EditorSearchMode.Text,
    EditorSearchMode.Regex,
)

internal fun editorSearchQueryLabel(query: String): String {
    val whitespaceOnly = query.isNotEmpty() && query.all(Char::isWhitespace)
    return buildString {
        var index = 0
        while (index < query.length) {
            val character = query[index]
            when {
                character == '\r' && query.getOrNull(index + 1) == '\n' -> {
                    append('↵')
                    index += 2
                }

                character == '\r' || character == '\n' -> {
                    append('↵')
                    index += 1
                }

                character == '\t' -> {
                    append('⇥')
                    index += 1
                }

                whitespaceOnly && character.isWhitespace() -> {
                    append('␠')
                    index += 1
                }

                else -> {
                    append(character)
                    index += 1
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    optionDescription: (T) -> String? = { null },
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorDialog(
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            EditorDialogAction(
                text = AppStrings.ui_cancel,
                onClick = onDismiss,
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                ListItem(
                    headlineContent = {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = if (isSelected) optionDescription(value)?.let { description ->
                        {
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else null,
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onSelected(value) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSearchPanel(
    query: String,
    replacement: String,
    mode: EditorSearchMode,
    scope: EditorSearchScope,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    direction: EditorSearchDirection,
    history: List<EditorSearchHistoryEntry>,
    activeMatchIndex: Int,
    results: List<EditorSearchResult>,
    totalCount: Long,
    detailsTruncated: Boolean,
    isSearching: Boolean,
    progress: Float,
    error: String?,
    canReplace: Boolean,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onModeChange: (EditorSearchMode) -> Unit,
    onScopeChange: (EditorSearchScope) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onWholeWordChange: (Boolean) -> Unit,
    onDirectionChange: (EditorSearchDirection) -> Unit,
    onHistorySelected: (EditorSearchHistoryEntry) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onDeleteHistory: (EditorSearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onResultSelected: (Int, EditorSearchResult) -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onDismiss: () -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val searchFocusRequester = remember { FocusRequester() }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    Column(modifier = modifier) {
        if (showHeader) {
            ListItem(
                headlineContent = { Text(AppStrings.ui_search_replace_files) },
                supportingContent = { Text(AppStrings.ui_supports_text_regular_matching_can_search_spaces_cross_line) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = onSearch,
                            enabled = !isSearching && parseEditorSearchQueries(query, mode).isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search)
                        }
                        IconButton(
                            onClick = {
                                if (isSearching) {
                                    onCancel()
                                } else {
                                    onDismiss()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = if (isSearching) {
                                    AppStrings.ui_cancel_search
                                } else {
                                    AppStrings.ui_close_search_panel
                                },
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
            HorizontalDivider()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                editorSearchUiModes.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = mode == value,
                        onClick = { onModeChange(value) },
                        enabled = !isSearching,
                        shape = SegmentedButtonDefaults.itemShape(index, editorSearchUiModes.size),
                        label = {
                            Text(
                                if (value == EditorSearchMode.Text) {
                                    AppStrings.ui_search_mode_text
                                } else {
                                    AppStrings.ui_search_mode_regex
                                },
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = !isSearching,
                label = { Text(AppStrings.ui_search_keywords) },
                placeholder = { Text(AppStrings.ui_enter_keyword) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty() && !isSearching) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = AppStrings.ui_clear_search_keywords)
                        }
                    }
                } else {
                    null
                },
                supportingText = {
                    Text(
                        when {
                            query.isNotEmpty() && query.all(Char::isWhitespace) ->
                                AppStrings.ui_current_search_arg0.format(arg0 = editorSearchQueryLabel(query))

                            mode == EditorSearchMode.Regex ->
                                AppStrings.ui_regex_input_preserves_newlines
                            else -> AppStrings.ui_text_will_searched_including_spaces_newlines
                        },
                    )
                },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
            )
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                enabled = !isSearching && canReplace,
                label = { Text(AppStrings.ui_replace) },
                placeholder = { Text(AppStrings.ui_leave_blank_delete_matching_content) },
                supportingText = {
                    Text(
                        if (mode == EditorSearchMode.Regex) {
                            AppStrings.ui_supports_1_name_capturing_group_templates
                        } else {
                            AppStrings.ui_replacement_content_written
                        }
                    )
                },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { onQueryChange(query + " ") },
                    enabled = !isSearching,
                    label = { Text(AppStrings.ui_space) },
                )
                AssistChip(
                    onClick = { onQueryChange(query + "\n") },
                    enabled = !isSearching,
                    label = { Text(AppStrings.ui_newline) },
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                EditorSearchScope.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = scope == value,
                        onClick = { onScopeChange(value) },
                        enabled = !isSearching,
                        shape = SegmentedButtonDefaults.itemShape(index, EditorSearchScope.entries.size),
                        label = {
                            Text(
                                when (value) {
                                    EditorSearchScope.CurrentPage -> AppStrings.ui_current_page
                                    EditorSearchScope.Selection -> AppStrings.ui_constituency
                                    EditorSearchScope.WholeFile -> AppStrings.ui_entire_file
                                }
                            )
                        },
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = caseSensitive,
                    onClick = { onCaseSensitiveChange(!caseSensitive) },
                    enabled = !isSearching,
                    label = { Text(AppStrings.ui_case_sensitive) },
                    leadingIcon = if (caseSensitive) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
                FilterChip(
                    selected = wholeWord,
                    onClick = { onWholeWordChange(!wholeWord) },
                    enabled = !isSearching,
                    label = { Text(AppStrings.ui_whole_word_match) },
                    leadingIcon = if (wholeWord) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            Text(AppStrings.ui_search_direction, style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val directions = listOf(
                    EditorSearchDirection.Forward,
                    EditorSearchDirection.Backward,
                )
                directions.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = direction == value,
                        onClick = { onDirectionChange(value) },
                        enabled = !isSearching,
                        shape = SegmentedButtonDefaults.itemShape(index, directions.size),
                        label = {
                            Text(
                                if (value == EditorSearchDirection.Forward) {
                                    AppStrings.ui_search_direction_forward
                                } else {
                                    AppStrings.ui_search_direction_backward
                                },
                            )
                        },
                    )
                }
            }
            if (!isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onReplaceCurrent,
                        enabled = canReplace && results.isNotEmpty(),
                    ) {
                        Text(AppStrings.ui_replace_current_item)
                    }
                    OutlinedButton(
                        onClick = onReplaceAll,
                        enabled = canReplace && results.isNotEmpty() && !detailsTruncated,
                    ) {
                        Text(AppStrings.ui_replace_all)
                    }
                }
            }
            if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = AppStrings.ui_recent_searches,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { showClearHistoryDialog = true },
                        enabled = !isSearching,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(AppStrings.ui_clear_all)
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.take(5).forEach { entry ->
                        val queryLabel = entry.queries.joinToString(" + ", transform = ::editorSearchQueryLabel)
                        InputChip(
                            selected = isEditorSearchHistoryEntrySelected(
                                entry = entry,
                                query = query,
                                mode = mode,
                                caseSensitive = caseSensitive,
                                wholeWord = wholeWord,
                                direction = direction,
                            ),
                            onClick = { onHistorySelected(entry) },
                            enabled = !isSearching,
                            label = {
                                Text(
                                    text = queryLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = AppStrings.ui_delete_search_history_arg0.format(arg0 = queryLabel),
                                    modifier = Modifier
                                        .size(InputChipDefaults.IconSize)
                                        .clickable(
                                            enabled = !isSearching,
                                            onClickLabel = AppStrings.ui_delete_search_history_arg0.format(arg0 = queryLabel),
                                            role = Role.Button,
                                        ) { onDeleteHistory(entry) },
                                )
                            },
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                    }
                }
            }
            if (isSearching) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = AppStrings.ui_search_progress_arg0.format(arg0 = ((progress * 100).toInt()).toString())
                        },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        error != null -> error
                        isSearching -> AppStrings.ui_searching_arg0_found_arg1_items.format(arg0 = ((progress * 100).toInt()).toString(), arg1 = (totalCount).toString())
                        totalCount == 0L -> AppStrings.ui_no_search_results
                        detailsTruncated -> AppStrings.ui_total_arg0_items_only_first_arg1_items_retained.format(arg0 = (totalCount).toString(), arg1 = (results.size).toString())
                        else -> AppStrings.ui_arg0_arg1_total_arg2_items.format(arg0 = (activeMatchIndex + 1).toString(), arg1 = (results.size).toString(), arg2 = (totalCount).toString())
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(onClick = onPrevious, enabled = results.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = AppStrings.ui_previous_match)
                }
                IconButton(onClick = onNext, enabled = results.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = AppStrings.ui_next_match)
                }
            }
            if (results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                ) {
                    itemsIndexed(
                        items = results,
                        key = { index, result ->
                            "$index:${result.startOffset}:${result.endOffsetExclusive}:${result.keyword}"
                        },
                        contentType = { _, _ -> "search-result" },
                    ) { index, result ->
                        val selected = index == activeMatchIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = AppStrings.ui_offset_arg0_arg1.format(arg0 = (result.startOffset).toString(), arg1 = editorSearchQueryLabel(result.keyword)),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = result.preview.ifBlank { AppStrings.ui_no_preview },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = AppStrings.ui_current_match,
                                    )
                                }
                            } else {
                                null
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                headlineColor = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                supportingColor = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultSelected(index, result) },
                        )
                    }
                }
            }
        }
    }
    if (showClearHistoryDialog) {
        EditorDialog(
            title = AppStrings.ui_clear_recent_searches,
            onDismissRequest = { showClearHistoryDialog = false },
            contentScrollable = false,
            forceFullScreen = false,
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_cancel,
                    onClick = { showClearHistoryDialog = false },
                )
                EditorDialogAction(
                    text = AppStrings.ui_clear_all,
                    onClick = {
                        showClearHistoryDialog = false
                        onClearHistory()
                    },
                )
            },
        ) {
            Text(
                AppStrings.ui_all_arg0_records_will_deleted_cannot_recovered_current_queries.format(arg0 = (history.size).toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun isEditorSearchHistoryEntrySelected(
    entry: EditorSearchHistoryEntry,
    query: String,
    mode: EditorSearchMode,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    direction: EditorSearchDirection,
): Boolean = query == entry.queries.joinToString("\n") &&
    mode == entry.mode &&
    caseSensitive == entry.caseSensitive &&
    wholeWord == entry.wholeWord &&
    direction == entry.direction

@Composable
private fun TextPageEditor(
    text: String,
    pageIndex: Long,
    verticalScrollState: ScrollState,
    readOnly: Boolean,
    searchMatch: EditorSearchMatch?,
    showLineNumbers: Boolean,
    automaticWrap: Boolean,
    showInvisibleCharacters: Boolean,
    selection: IntRange?,
    onTextChange: (String) -> Boolean,
    onSelectionChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace,
    )
    var fieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    var fieldFocused by remember { mutableStateOf(false) }
    var pendingSelectionCollapse by remember {
        mutableStateOf<PendingEditorSelectionCollapse?>(null)
    }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val horizontalScrollState = remember(pageIndex) { ScrollState(0) }
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)

    LaunchedEffect(text) {
        if (fieldValue.text != text) fieldValue = TextFieldValue(text)
    }
    LaunchedEffect(searchMatch) {
        searchMatch?.let { match ->
            fieldValue = fieldValue.copy(
                selection = TextRange(
                    start = match.start.coerceIn(0, fieldValue.text.length),
                    end = (match.start + match.length).coerceIn(0, fieldValue.text.length),
                )
            )
        }
    }
    LaunchedEffect(selection) {
        selection?.let { range ->
            val start = range.first.coerceIn(0, fieldValue.text.length)
            val end = range.last.coerceIn(0, fieldValue.text.length)
            if (fieldValue.selection.start != start || fieldValue.selection.end != end) {
                fieldValue = fieldValue.copy(selection = TextRange(start, end))
            }
        }
    }
    LaunchedEffect(pendingSelectionCollapse) {
        val pending = pendingSelectionCollapse ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (pendingSelectionCollapse != pending) return@LaunchedEffect
        if (fieldFocused) {
            pendingSelectionCollapse = null
            latestOnSelectionChange(pending.collapsed.start, pending.collapsed.end)
        } else {
            fieldValue = fieldValue.copy(selection = pending.previous)
            pendingSelectionCollapse = null
            latestOnSelectionChange(pending.previous.start, pending.previous.end)
        }
    }
    val persistedSelection = selection?.let { range ->
        TextRange(
            start = range.first.coerceIn(0, fieldValue.text.length),
            end = range.last.coerceIn(0, fieldValue.text.length),
        )
    }
    val selectionBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val visualTransformation = remember(
        showInvisibleCharacters,
        fieldFocused,
        persistedSelection,
        selectionBackground,
    ) {
        EditorTextVisualTransformation(
            showInvisibleCharacters = showInvisibleCharacters,
            persistentSelection = persistedSelection.takeUnless { fieldFocused },
            selectionBackground = selectionBackground,
        )
    }

    val lineNumberText = remember(
        fieldValue.text,
        textLayoutResult,
        showInvisibleCharacters,
    ) {
        editorLineNumberText(
            text = fieldValue.text,
            layoutResult = textLayoutResult,
            showInvisibleCharacters = showInvisibleCharacters,
        )
    }

    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        val editorViewportHeight = maxHeight
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState),
                verticalAlignment = Alignment.Top,
            ) {
                if (showLineNumbers) {
                    Text(
                        text = lineNumberText,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        softWrap = false,
                        modifier = Modifier
                            .heightIn(min = editorViewportHeight)
                            .padding(start = 8.dp, top = 16.dp, bottom = 16.dp)
                            .semantics { contentDescription = AppStrings.ui_current_page_line_number },
                    )
                    Spacer(Modifier.width(12.dp))
                }
                val horizontalScrollModifier = if (automaticWrap) {
                    Modifier
                } else {
                    Modifier
                        .shiftWheelHorizontalScroll(horizontalScrollState)
                        .horizontalScroll(horizontalScrollState)
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        if (shouldDeferEditorSelectionCollapse(fieldValue, updated, fieldFocused)) {
                            pendingSelectionCollapse = PendingEditorSelectionCollapse(
                                previous = fieldValue.selection,
                                collapsed = updated.selection,
                            )
                            fieldValue = updated
                            return@BasicTextField
                        }
                        pendingSelectionCollapse = null
                        val effectiveValue = preserveEditorSelectionAfterFocusLoss(
                            current = fieldValue,
                            updated = updated,
                            focused = fieldFocused,
                        )
                        if (readOnly || onTextChange(effectiveValue.text)) {
                            fieldValue = effectiveValue
                            onSelectionChange(
                                effectiveValue.selection.start,
                                effectiveValue.selection.end,
                            )
                        }
                    },
                    readOnly = readOnly,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = visualTransformation,
                    onTextLayout = { layoutResult ->
                        textLayoutResult = layoutResult
                    },
                    modifier = horizontalScrollModifier
                        .weight(1f)
                        .heightIn(min = editorViewportHeight)
                        .onFocusChanged { focusState ->
                            fieldFocused = focusState.isFocused
                            if (!focusState.isFocused) {
                                pendingSelectionCollapse?.let { pending ->
                                    fieldValue = fieldValue.copy(selection = pending.previous)
                                    pendingSelectionCollapse = null
                                    latestOnSelectionChange(
                                        pending.previous.start,
                                        pending.previous.end,
                                    )
                                }
                            }
                        }
                        .padding(top = 16.dp, end = 16.dp, bottom = 16.dp)
                        .semantics {
                            contentDescription = if (readOnly) AppStrings.ui_text_content_read_only else AppStrings.ui_text_content_editor
                        },
                )
            }

            EditorScrollIndicator(
                scrollState = verticalScrollState,
                orientation = EditorScrollOrientation.Vertical,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(8.dp)
                    .padding(top = 4.dp, end = 2.dp, bottom = 4.dp),
            )
            if (!automaticWrap) {
                EditorScrollIndicator(
                    scrollState = horizontalScrollState,
                    orientation = EditorScrollOrientation.Horizontal,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(8.dp)
                        .padding(start = 4.dp, end = 10.dp, bottom = 2.dp),
                )
            }
        }
    }
}

internal data class PendingEditorSelectionCollapse(
    val previous: TextRange,
    val collapsed: TextRange,
)

internal fun shouldDeferEditorSelectionCollapse(
    current: TextFieldValue,
    updated: TextFieldValue,
    focused: Boolean,
): Boolean = focused &&
    current.text == updated.text &&
    !current.selection.collapsed &&
    updated.selection.collapsed

internal fun preserveEditorSelectionAfterFocusLoss(
    current: TextFieldValue,
    updated: TextFieldValue,
    focused: Boolean,
): TextFieldValue {
    val collapsedByFocusLoss = !focused &&
        current.text == updated.text &&
        !current.selection.collapsed &&
        updated.selection.collapsed
    return if (collapsedByFocusLoss) {
        updated.copy(selection = current.selection)
    } else {
        updated
    }
}

private fun editorLineNumberText(
    text: String,
    layoutResult: TextLayoutResult?,
    showInvisibleCharacters: Boolean,
): String {
    val transformedLineStarts = buildList {
        add(0)
        var transformedOffset = 0
        text.forEach { character ->
            transformedOffset += if (showInvisibleCharacters && character == '\n') 2 else 1
            if (character == '\n') add(transformedOffset)
        }
    }
    if (layoutResult == null) {
        return transformedLineStarts.indices.joinToString("\n") { (it + 1).toString() }
    }

    val visualLineNumbers = IntArray(layoutResult.lineCount.coerceAtLeast(1))
    val transformedTextLength = layoutResult.layoutInput.text.length
    transformedLineStarts.forEachIndexed { logicalLineIndex, transformedOffset ->
        val visualLine = layoutResult.getLineForOffset(
            transformedOffset.coerceIn(0, transformedTextLength),
        )
        if (visualLine in visualLineNumbers.indices) {
            visualLineNumbers[visualLine] = logicalLineIndex + 1
        }
    }
    return visualLineNumbers.joinToString("\n") { lineNumber ->
        lineNumber.takeIf { it > 0 }?.toString().orEmpty()
    }
}

private enum class EditorScrollOrientation {
    Vertical,
    Horizontal,
}

private fun Modifier.editorPageTurnWheelScroll(
    scrollState: ScrollState,
    handler: EditorPageTurnScrollHandler,
): Modifier = pointerInput(scrollState, handler) {
    val scrollStep = 48.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll || event.keyboardModifiers.isShiftPressed) {
                continue
            }

            val scrollDelta = event.changes.fold(Offset.Zero) { total, change ->
                total + change.scrollDelta
            }
            if (abs(scrollDelta.y) <= abs(scrollDelta.x) || scrollDelta.y == 0f) continue

            val handled = handler.onWheelEvent(
                scrollDelta.y * scrollStep,
                !scrollState.canScrollBackward,
                !scrollState.canScrollForward,
            )
            if (handled) {
                event.changes.forEach { change -> change.consume() }
            }
            try {
                awaitPointerEvent(PointerEventPass.Final)
            } finally {
                handler.onWheelEventFinished()
            }
        }
    }
}

private fun Modifier.shiftWheelHorizontalScroll(scrollState: ScrollState): Modifier =
    pointerInput(scrollState) {
        val scrollStep = 48.dp.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isShiftPressed) {
                    continue
                }

                val scrollDelta = event.changes.fold(Offset.Zero) { total, change ->
                    total + change.scrollDelta
                }
                if (abs(scrollDelta.y) <= abs(scrollDelta.x)) continue

                val consumed = scrollState.dispatchRawDelta(scrollDelta.y * scrollStep)
                if (consumed != 0f) {
                    event.changes.forEach { change -> change.consume() }
                }
            }
        }
    }

private data class EditorScrollThumbMetrics(
    val thumbLength: Float,
    val thumbOffset: Float,
    val travelLength: Float,
)

private fun editorScrollThumbMetrics(
    scrollState: ScrollState,
    viewportLength: Float,
    minimumThumbLength: Float,
): EditorScrollThumbMetrics {
    val scrollRange = scrollState.maxValue.toFloat()
    val contentLength = viewportLength + scrollRange
    val thumbLength = max(
        minimumThumbLength.coerceAtMost(viewportLength),
        viewportLength * viewportLength / contentLength,
    ).coerceAtMost(viewportLength)
    val travelLength = (viewportLength - thumbLength).coerceAtLeast(0f)
    val thumbOffset = if (scrollRange > 0f) {
        travelLength * scrollState.value.toFloat() / scrollRange
    } else {
        0f
    }
    return EditorScrollThumbMetrics(
        thumbLength = thumbLength,
        thumbOffset = thumbOffset,
        travelLength = travelLength,
    )
}

private fun ScrollState.scrollToThumbPosition(
    pointerPosition: Float,
    pointerOffsetInThumb: Float,
    metrics: EditorScrollThumbMetrics,
) {
    if (maxValue <= 0 || metrics.travelLength <= 0f) return
    val targetThumbOffset = (pointerPosition - pointerOffsetInThumb)
        .coerceIn(0f, metrics.travelLength)
    val targetValue = targetThumbOffset / metrics.travelLength * maxValue
    dispatchRawDelta(targetValue - value)
}

@Composable
private fun EditorScrollIndicator(
    scrollState: ScrollState,
    orientation: EditorScrollOrientation,
    modifier: Modifier = Modifier,
) {
    if (scrollState.maxValue <= 0) return

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val interactiveModifier = modifier.pointerInput(scrollState, orientation) {
        val minimumThumbLength = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val viewportLength = when (orientation) {
                EditorScrollOrientation.Vertical -> size.height.toFloat()
                EditorScrollOrientation.Horizontal -> size.width.toFloat()
            }
            if (viewportLength <= 0f || scrollState.maxValue <= 0) return@awaitEachGesture

            val metrics = editorScrollThumbMetrics(
                scrollState = scrollState,
                viewportLength = viewportLength,
                minimumThumbLength = minimumThumbLength,
            )
            val pointerPosition = when (orientation) {
                EditorScrollOrientation.Vertical -> down.position.y
                EditorScrollOrientation.Horizontal -> down.position.x
            }
            val pointerOffsetInThumb = if (
                pointerPosition in metrics.thumbOffset..(metrics.thumbOffset + metrics.thumbLength)
            ) {
                pointerPosition - metrics.thumbOffset
            } else {
                metrics.thumbLength / 2f
            }
            scrollState.scrollToThumbPosition(
                pointerPosition = pointerPosition,
                pointerOffsetInThumb = pointerOffsetInThumb,
                metrics = metrics,
            )
            down.consume()

            drag(down.id) { change ->
                val dragPosition = when (orientation) {
                    EditorScrollOrientation.Vertical -> change.position.y
                    EditorScrollOrientation.Horizontal -> change.position.x
                }
                val currentMetrics = editorScrollThumbMetrics(
                    scrollState = scrollState,
                    viewportLength = viewportLength,
                    minimumThumbLength = minimumThumbLength,
                )
                scrollState.scrollToThumbPosition(
                    pointerPosition = dragPosition,
                    pointerOffsetInThumb = pointerOffsetInThumb,
                    metrics = currentMetrics,
                )
                change.consume()
            }
        }
    }
    Canvas(modifier = interactiveModifier) {
        val viewportLength = when (orientation) {
            EditorScrollOrientation.Vertical -> size.height
            EditorScrollOrientation.Horizontal -> size.width
        }
        if (viewportLength <= 0f) return@Canvas

        val metrics = editorScrollThumbMetrics(
            scrollState = scrollState,
            viewportLength = viewportLength,
            minimumThumbLength = 24.dp.toPx(),
        )
        val thickness = 3.dp.toPx()
        val cornerRadius = CornerRadius(thickness / 2f)

        when (orientation) {
            EditorScrollOrientation.Vertical -> {
                val x = (size.width - thickness) / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(thickness, viewportLength),
                    cornerRadius = cornerRadius,
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(x, metrics.thumbOffset),
                    size = Size(thickness, metrics.thumbLength),
                    cornerRadius = cornerRadius,
                )
            }

            EditorScrollOrientation.Horizontal -> {
                val y = (size.height - thickness) / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, y),
                    size = Size(viewportLength, thickness),
                    cornerRadius = cornerRadius,
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(metrics.thumbOffset, y),
                    size = Size(metrics.thumbLength, thickness),
                    cornerRadius = cornerRadius,
                )
            }
        }
    }
}

@Composable
private fun FileEditorBottomBar(
    currentPage: Long,
    pageCount: Long,
    enabled: Boolean,
    showEditingActions: Boolean,
    showPageNavigation: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    saveEnabled: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelected: (Long) -> Unit,
    onLineSelected: (Long) -> Unit,
    onByteOffsetSelected: (Long) -> Unit,
    onPercentageSelected: (Double) -> Unit,
    currentLineNumber: Long?,
    lineNumberProvisional: Boolean,
    modifier: Modifier = Modifier,
) {
    val normalizedPageCount = pageCount.coerceAtLeast(1L)
    val displayPage = (currentPage + 1L).coerceIn(1L, normalizedPageCount)
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpTarget by remember { mutableStateOf(EditorJumpTarget.Page) }
    var jumpInput by remember(displayPage, jumpTarget) {
        mutableStateOf(if (jumpTarget == EditorJumpTarget.Page) displayPage.toString() else "")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showEditingActions) {
                    IconButton(
                        onClick = onUndo,
                        enabled = enabled && canUndo,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = AppStrings.editor_cancel_action,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = enabled && canRedo,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = AppStrings.ui_redo,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onSave,
                        enabled = saveEnabled,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = AppStrings.ui_save_file,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (!compact || !showPageNavigation) {
                    Spacer(Modifier.weight(1f))
                }
                if (showPageNavigation) {
                    IconButton(
                        onClick = onPreviousPage,
                        enabled = enabled && currentPage > 0L,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = AppStrings.ui_previous_page,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(
                        onClick = { showJumpDialog = true },
                        enabled = enabled,
                        modifier = if (compact) Modifier.weight(1f) else Modifier,
                    ) {
                        Text(
                            text = AppStrings.ui_page_arg0_arg1.format(arg0 = (displayPage).toString(), arg1 = (normalizedPageCount).toString()),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onNextPage,
                        enabled = enabled && currentPage + 1L < pageCount,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = AppStrings.ui_next_page,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (showPageNavigation) {
            LinearProgressIndicator(
                progress = {
                    if (normalizedPageCount <= 1L) 1f
                    else (currentPage.toFloat() / (normalizedPageCount - 1L).toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }

    if (showJumpDialog) {
        val targetPage = jumpInput.toLongOrNull()
        val targetLine = jumpInput.toLongOrNull()
        val targetOffset = jumpInput.toLongOrNull()
        val targetPercentage = jumpInput.toDoubleOrNull()
        val inputValid = when (jumpTarget) {
            EditorJumpTarget.Page -> targetPage != null && targetPage in 1L..normalizedPageCount
            EditorJumpTarget.Line -> targetLine != null && targetLine >= 1L
            EditorJumpTarget.ByteOffset -> targetOffset != null && targetOffset >= 0L
            EditorJumpTarget.Percentage ->
                targetPercentage != null && targetPercentage.isFinite() && targetPercentage in 0.0..100.0
        }
        val performJump: () -> Unit = {
            if (inputValid) {
                when (jumpTarget) {
                    EditorJumpTarget.Page -> targetPage?.let { onPageSelected(it - 1L) }
                    EditorJumpTarget.Line -> targetLine?.let(onLineSelected)
                    EditorJumpTarget.ByteOffset -> targetOffset?.let(onByteOffsetSelected)
                    EditorJumpTarget.Percentage -> targetPercentage?.let(onPercentageSelected)
                }
                showJumpDialog = false
            }
        }
        val jumpFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            jumpFocusRequester.requestFocus()
        }
        EditorDialog(
            title = AppStrings.ui_quick_jump,
            onDismissRequest = { showJumpDialog = false },
            actions = {
                EditorDialogAction(
                    text = AppStrings.ui_cancel,
                    onClick = { showJumpDialog = false },
                )
                EditorDialogAction(
                    text = AppStrings.ui_jump,
                    onClick = performJump,
                    enabled = inputValid,
                )
            },
        ) {
            EditorDialogSection(
                title = AppStrings.ui_current_location,
                tone = EditorDialogSectionTone.Accent,
            ) {
                Text(
                    buildString {
                        append(AppStrings.ui_current_page_arg0_arg1.format(arg0 = (displayPage).toString(), arg1 = (normalizedPageCount).toString()))
                        currentLineNumber?.let { line ->
                            append(" · ")
                            if (lineNumberProvisional) append(AppStrings.ui_approx)
                            append(AppStrings.ui_line_arg0.format(arg0 = (line).toString()))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            EditorDialogSection(title = AppStrings.ui_jump_target) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorJumpTarget.entries.forEach { target ->
                        FilterChip(
                            selected = jumpTarget == target,
                            onClick = {
                                jumpTarget = target
                                jumpInput = if (target == EditorJumpTarget.Page) {
                                    displayPage.toString()
                                } else {
                                    ""
                                }
                            },
                            label = { Text(target.displayName()) },
                            leadingIcon = if (jumpTarget == target) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    OutlinedTextField(
                        value = jumpInput,
                        onValueChange = { input ->
                            jumpInput = when (jumpTarget) {
                                EditorJumpTarget.Percentage -> input.filter { it.isDigit() || it == '.' }
                                else -> input.filter(Char::isDigit)
                            }.take(18)
                        },
                        label = {
                            Text(
                                when (jumpTarget) {
                                    EditorJumpTarget.Page -> AppStrings.ui_page_number_1_arg0.format(arg0 = (normalizedPageCount).toString())
                                    EditorJumpTarget.Line -> AppStrings.ui_line_number_starting_1
                                    EditorJumpTarget.ByteOffset -> AppStrings.ui_absolute_byte_offset
                                    EditorJumpTarget.Percentage -> AppStrings.ui_file_position_0_100
                                }
                            )
                        },
                        supportingText = if (inputValid || jumpInput.isBlank()) null else {
                            { Text(AppStrings.ui_please_enter_valid_jump_location) }
                        },
                        isError = jumpInput.isNotBlank() && !inputValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (jumpTarget == EditorJumpTarget.Percentage) {
                                KeyboardType.Decimal
                            } else {
                                KeyboardType.Number
                            },
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { performJump() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(jumpFocusRequester),
                    )
                }
            }
        }
    }
}

internal fun shouldShowFileEditorPageNavigation(pageCount: Long): Boolean = pageCount > 1L

internal fun shouldShowFileEditorBottomBar(canWrite: Boolean, pageCount: Long): Boolean =
    canWrite || shouldShowFileEditorPageNavigation(pageCount)

internal fun findTextMatches(text: String, query: String): List<EditorSearchMatch> = buildList {
    var searchFrom = 0
    while (searchFrom <= text.length - query.length) {
        val matchStart = text.indexOf(query, startIndex = searchFrom, ignoreCase = true)
        if (matchStart < 0) break
        add(EditorSearchMatch(matchStart, query.length))
        searchFrom = matchStart + query.length.coerceAtLeast(1)
    }
}

internal class EditorTextVisualTransformation(
    private val showInvisibleCharacters: Boolean,
    private val persistentSelection: TextRange?,
    private val selectionBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = if (showInvisibleCharacters) {
            InvisibleCharacterTransformation.filter(text)
        } else {
            VisualTransformation.None.filter(text)
        }
        val selection = persistentSelection ?: return transformed
        val originalStart = minOf(selection.start, selection.end).coerceIn(0, text.length)
        val originalEnd = maxOf(selection.start, selection.end).coerceIn(originalStart, text.length)
        if (originalStart == originalEnd) return transformed
        val transformedStart = transformed.offsetMapping.originalToTransformed(originalStart)
        val transformedEnd = transformed.offsetMapping.originalToTransformed(originalEnd)
        val highlighted = AnnotatedString.Builder(transformed.text).apply {
            addStyle(
                SpanStyle(background = selectionBackground),
                transformedStart,
                transformedEnd,
            )
        }.toAnnotatedString()
        return TransformedText(highlighted, transformed.offsetMapping)
    }
}

private object InvisibleCharacterTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val transformed = buildString(original.length + original.count { it == '\n' }) {
            original.forEach { char ->
                when (char) {
                    ' ' -> append('·')
                    '\t' -> append('→')
                    '\n' -> append("¶\n")
                    else -> append(char)
                }
            }
        }
        val originalToTransformed = IntArray(original.length + 1)
        val transformedToOriginal = IntArray(transformed.length + 1)
        var transformedOffset = 0
        original.forEachIndexed { originalOffset, char ->
            originalToTransformed[originalOffset] = transformedOffset
            transformedToOriginal[transformedOffset] = originalOffset
            if (char == '\n') {
                transformedToOriginal[transformedOffset + 1] = originalOffset
                transformedOffset += 2
            } else {
                transformedOffset += 1
            }
            transformedToOriginal[transformedOffset] = originalOffset + 1
        }
        originalToTransformed[original.length] = transformedOffset
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed[offset.coerceIn(0, original.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal[offset.coerceIn(0, transformed.length)]
        }
        return TransformedText(AnnotatedString(transformed), mapping)
    }
}

private fun EditorTextEncoding.displayName(): String = when (this) {
    EditorTextEncoding.ASCII -> "ASCII"
    EditorTextEncoding.UTF8 -> "UTF-8"
    EditorTextEncoding.UTF16_LE -> "UTF-16 LE"
    EditorTextEncoding.UTF16_BE -> "UTF-16 BE"
    EditorTextEncoding.GBK -> "GBK"
    EditorTextEncoding.ISO_8859_1 -> "ISO-8859-1"
}

private fun EditorNewlineKind.displayName(): String = when (this) {
    EditorNewlineKind.None -> AppStrings.ui_none
    EditorNewlineKind.LF -> "LF"
    EditorNewlineKind.CRLF -> "CRLF"
    EditorNewlineKind.CR -> "CR"
    EditorNewlineKind.Mixed -> AppStrings.ui_mix
}

private fun EditorModificationKind.displayName(): String = when (this) {
    EditorModificationKind.TextReplacement -> AppStrings.ui_text_replacement
}

private fun EditorModification.byteRangeLabel(): String =
    AppStrings.ui_0x_arg0_0x_arg1_excluding_end.format(arg0 = startOffset.toString(16).uppercase(), arg1 = endOffsetExclusive.toString(16).uppercase())

private fun Long.editorByteCountLabel(): String = AppStrings.editor_file_size_and_bytes_arg0_arg1.format(arg0 = formatFileSize(), arg1 = (this).toString())

private fun Long.signedByteDeltaLabel(): String =
    AppStrings.editor_signed_byte_delta_arg0_arg1.format(arg0 = if (this >= 0L) "+" else "", arg1 = (this).toString())

private fun EditorBackupMode.displayName(): String = when (this) {
    EditorBackupMode.CompleteFile -> AppStrings.ui_full_file_backup
    EditorBackupMode.ModifiedRanges -> AppStrings.ui_back_up_only_modified_range
    EditorBackupMode.Disabled -> AppStrings.ui_don_t_create_backup
}

private fun EditorJumpTarget.displayName(): String = when (this) {
    EditorJumpTarget.Page -> AppStrings.ui_page_number
    EditorJumpTarget.Line -> AppStrings.ui_line_number
    EditorJumpTarget.ByteOffset -> AppStrings.ui_byte_offset
    EditorJumpTarget.Percentage -> AppStrings.ui_file_percentage
}
