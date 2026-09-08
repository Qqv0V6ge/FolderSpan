package com.folderspan.ui.screen.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.error.ErrorEmptyData
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.components.text.HighlightedText
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import strings.AppStrings

private object TaskResultTokens {
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 16.dp
    val spacingLg = 24.dp
    val minimumTouchTarget = 48.dp
    val taskIconContainerSize = 56.dp
    val taskIconSize = 28.dp
    val emptyStateMinHeight = 220.dp
    val messageFilterMaxWidth = 280.dp
    val infoPanelWidth = 320.dp
    val resultGridMinItemWidth = 360.dp
    val floatingActionButtonClearance = 96.dp
}

internal data class TaskResultListItem(
    val stableKey: String,
    val path: String,
    val message: String,
)

internal fun buildTaskResultListItems(results: List<Pair<String, String>>): List<TaskResultListItem> {
    val pathOccurrences = mutableMapOf<String, Int>()
    return results.map { (path, message) ->
        val occurrence = pathOccurrences.getOrElse(path) { 0 }
        pathOccurrences[path] = occurrence + 1
        TaskResultListItem(
            stableKey = "task-result:$path\u0000$occurrence",
            path = path,
            message = message,
        )
    }
}

internal fun filterTaskResultListItems(
    items: List<TaskResultListItem>,
    searchText: String,
    useRegex: Boolean,
    searchRegex: Regex?,
    selectedMessageTypes: Set<String>,
): List<TaskResultListItem> = items.filter { item ->
    val matchesSearch = when {
        searchText.isEmpty() -> true
        useRegex -> searchRegex?.containsMatchIn(item.path) ?: false
        else -> item.path.contains(searchText, ignoreCase = true)
    }
    matchesSearch &&
        (selectedMessageTypes.isEmpty() || item.message in selectedMessageTypes)
}

class TaskResultScreen(
    private val task: Task,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val taskState = koinInject<TaskState>()

        val revision = taskState.revision
        val currentTask = remember(revision) {
            taskState.tasks.firstOrNull { item -> item.key == task.key }
        }
        val displayTask = currentTask ?: task
        val displayResults by produceState<List<Pair<String, String>>?>(
            initialValue = null,
            key1 = displayTask.key,
            key2 = revision,
        ) {
            value = withContext(Dispatchers.Default) {
                taskState.loadFailureDisplayResultsSnapshot(displayTask)
            }
        }

        var searchInputText by remember { mutableStateOf("") }
        var useRegexInput by remember { mutableStateOf(false) }
        var appliedSearchText by remember { mutableStateOf("") }
        var appliedUseRegex by remember { mutableStateOf(false) }
        var selectedMessageTypes by remember { mutableStateOf(setOf<String>()) }

        val resultItems = remember(displayResults) {
            buildTaskResultListItems(displayResults.orEmpty())
        }
        val messageTypeCounts = remember(resultItems) {
            resultItems.groupingBy(TaskResultListItem::message).eachCount()
        }
        val messageTypeItems = remember(messageTypeCounts) { messageTypeCounts.keys.toList() }

        LaunchedEffect(messageTypeCounts.keys) {
            selectedMessageTypes = selectedMessageTypes.intersect(messageTypeCounts.keys)
        }

        val inputSearchRegex = remember(searchInputText, useRegexInput) {
            if (!useRegexInput || searchInputText.isEmpty()) {
                null
            } else {
                runCatching { Regex(searchInputText, RegexOption.IGNORE_CASE) }.getOrNull()
            }
        }
        val searchErrorText = if (
            useRegexInput && searchInputText.isNotEmpty() && inputSearchRegex == null
        ) {
            AppStrings.ui_regular_expression_error
        } else {
            ""
        }
        val appliedSearchRegex = remember(appliedSearchText, appliedUseRegex) {
            if (!appliedUseRegex || appliedSearchText.isEmpty()) {
                null
            } else {
                runCatching { Regex(appliedSearchText, RegexOption.IGNORE_CASE) }.getOrNull()
            }
        }
        val filteredResults = remember(
            resultItems,
            appliedSearchText,
            appliedUseRegex,
            appliedSearchRegex,
            selectedMessageTypes,
        ) {
            filterTaskResultListItems(
                items = resultItems,
                searchText = appliedSearchText,
                useRegex = appliedUseRegex,
                searchRegex = appliedSearchRegex,
                selectedMessageTypes = selectedMessageTypes,
            )
        }
        val hasPendingOrActiveFilters = searchInputText.isNotEmpty() ||
            useRegexInput ||
            appliedSearchText.isNotEmpty() ||
            appliedUseRegex ||
            selectedMessageTypes.isNotEmpty()
        val isLoading = displayResults == null

        fun applySearch() {
            if (searchErrorText.isNotEmpty()) return
            appliedSearchText = searchInputText
            appliedUseRegex = useRegexInput
        }

        fun resetFilters() {
            searchInputText = ""
            useRegexInput = false
            appliedSearchText = ""
            appliedUseRegex = false
            selectedMessageTypes = emptySet()
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_task_results) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        taskState.delete(displayTask)
                        navigator.pop()
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                        )
                    },
                    text = { Text(AppStrings.ui_delete_task) },
                )
            },
        ) { paddingValues ->
            TaskResultPage(
                task = displayTask,
                resultItems = resultItems,
                filteredResults = filteredResults,
                messageTypeItems = messageTypeItems,
                messageTypeCounts = messageTypeCounts,
                selectedMessageTypes = selectedMessageTypes,
                searchInputText = searchInputText,
                useRegexInput = useRegexInput,
                searchErrorText = searchErrorText,
                appliedSearchText = appliedSearchText,
                appliedUseRegex = appliedUseRegex,
                isLoading = isLoading,
                canResetFilters = hasPendingOrActiveFilters,
                onSearchInputChange = { searchInputText = it },
                onRegexInputChange = { useRegexInput = it },
                onSearch = ::applySearch,
                onResetFilters = ::resetFilters,
                onMessageTypeToggle = { type ->
                    selectedMessageTypes = if (type in selectedMessageTypes) {
                        selectedMessageTypes - type
                    } else {
                        selectedMessageTypes + type
                    }
                },
                onSelectAllMessageTypes = { selectedMessageTypes = emptySet() },
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TaskResultPage(
    task: Task,
    resultItems: List<TaskResultListItem>,
    filteredResults: List<TaskResultListItem>,
    messageTypeItems: List<String>,
    messageTypeCounts: Map<String, Int>,
    selectedMessageTypes: Set<String>,
    searchInputText: String,
    useRegexInput: Boolean,
    searchErrorText: String,
    appliedSearchText: String,
    appliedUseRegex: Boolean,
    isLoading: Boolean,
    canResetFilters: Boolean,
    onSearchInputChange: (String) -> Unit,
    onRegexInputChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onResetFilters: () -> Unit,
    onMessageTypeToggle: (String) -> Unit,
    onSelectAllMessageTypes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val compactLayout = maxWidth < 600.dp
        if (compactLayout) {
            TaskResultCompactLayout(
                task = task,
                resultItems = resultItems,
                filteredResults = filteredResults,
                messageTypeItems = messageTypeItems,
                messageTypeCounts = messageTypeCounts,
                selectedMessageTypes = selectedMessageTypes,
                searchInputText = searchInputText,
                useRegexInput = useRegexInput,
                searchErrorText = searchErrorText,
                appliedSearchText = appliedSearchText,
                appliedUseRegex = appliedUseRegex,
                isLoading = isLoading,
                canResetFilters = canResetFilters,
                onSearchInputChange = onSearchInputChange,
                onRegexInputChange = onRegexInputChange,
                onSearch = onSearch,
                onResetFilters = onResetFilters,
                onMessageTypeToggle = onMessageTypeToggle,
                onSelectAllMessageTypes = onSelectAllMessageTypes,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TaskResultWideLayout(
                task = task,
                resultItems = resultItems,
                filteredResults = filteredResults,
                messageTypeItems = messageTypeItems,
                messageTypeCounts = messageTypeCounts,
                selectedMessageTypes = selectedMessageTypes,
                searchInputText = searchInputText,
                useRegexInput = useRegexInput,
                searchErrorText = searchErrorText,
                appliedSearchText = appliedSearchText,
                appliedUseRegex = appliedUseRegex,
                isLoading = isLoading,
                canResetFilters = canResetFilters,
                onSearchInputChange = onSearchInputChange,
                onRegexInputChange = onRegexInputChange,
                onSearch = onSearch,
                onResetFilters = onResetFilters,
                onMessageTypeToggle = onMessageTypeToggle,
                onSelectAllMessageTypes = onSelectAllMessageTypes,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TaskResultCompactLayout(
    task: Task,
    resultItems: List<TaskResultListItem>,
    filteredResults: List<TaskResultListItem>,
    messageTypeItems: List<String>,
    messageTypeCounts: Map<String, Int>,
    selectedMessageTypes: Set<String>,
    searchInputText: String,
    useRegexInput: Boolean,
    searchErrorText: String,
    appliedSearchText: String,
    appliedUseRegex: Boolean,
    isLoading: Boolean,
    canResetFilters: Boolean,
    onSearchInputChange: (String) -> Unit,
    onRegexInputChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onResetFilters: () -> Unit,
    onMessageTypeToggle: (String) -> Unit,
    onSelectAllMessageTypes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val contentModifier = Modifier.fillMaxWidth()
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = TaskResultTokens.spacingMd,
            top = 0.dp,
            end = TaskResultTokens.spacingMd,
            bottom = TaskResultTokens.floatingActionButtonClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(
            key = "task-result-info",
            contentType = "info",
        ) {
            TaskResultInfoPanel(
                task = task,
                resultCount = resultItems.size,
                isLoading = isLoading,
                searchInputText = searchInputText,
                useRegexInput = useRegexInput,
                searchErrorText = searchErrorText,
                messageTypeItems = messageTypeItems,
                messageTypeCounts = messageTypeCounts,
                selectedMessageTypes = selectedMessageTypes,
                canResetFilters = canResetFilters,
                onSearchInputChange = onSearchInputChange,
                onRegexInputChange = onRegexInputChange,
                onSearch = onSearch,
                onResetFilters = onResetFilters,
                onMessageTypeToggle = onMessageTypeToggle,
                onSelectAllMessageTypes = onSelectAllMessageTypes,
                modifier = contentModifier,
            )
        }

        item(
            key = "task-result-list-header",
            contentType = "list-header",
        ) {
            TaskResultListHeader(
                visibleCount = filteredResults.size,
                totalCount = resultItems.size,
                modifier = contentModifier,
            )
        }

        when {
            isLoading -> {
                item(key = "task-result-loading", contentType = "loading") {
                    TaskResultLoadingState(modifier = contentModifier)
                }
            }

            resultItems.isEmpty() -> {
                item(key = "task-result-empty", contentType = "empty") {
                    ErrorEmptyData(
                        message = AppStrings.task_results_empty,
                        modifier = contentModifier
                            .heightIn(min = TaskResultTokens.emptyStateMinHeight),
                    )
                }
            }

            filteredResults.isEmpty() -> {
                item(key = "task-result-no-matches", contentType = "empty") {
                    ErrorEmptyData(
                        message = AppStrings.task_results_no_matches,
                        supportingText = AppStrings.task_results_adjust_filters,
                        actionLabel = AppStrings.ui_reset,
                        onAction = onResetFilters,
                        modifier = contentModifier
                            .heightIn(min = TaskResultTokens.emptyStateMinHeight),
                    )
                }
            }

            else -> {
                items(
                    items = filteredResults,
                    key = TaskResultListItem::stableKey,
                    contentType = { "result" },
                ) { item ->
                    TaskResultItem(
                        item = item,
                        searchText = appliedSearchText,
                        useRegex = appliedUseRegex,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskResultWideLayout(
    task: Task,
    resultItems: List<TaskResultListItem>,
    filteredResults: List<TaskResultListItem>,
    messageTypeItems: List<String>,
    messageTypeCounts: Map<String, Int>,
    selectedMessageTypes: Set<String>,
    searchInputText: String,
    useRegexInput: Boolean,
    searchErrorText: String,
    appliedSearchText: String,
    appliedUseRegex: Boolean,
    isLoading: Boolean,
    canResetFilters: Boolean,
    onSearchInputChange: (String) -> Unit,
    onRegexInputChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onResetFilters: () -> Unit,
    onMessageTypeToggle: (String) -> Unit,
    onSelectAllMessageTypes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = TaskResultTokens.spacingLg),
    ) {
        TaskResultInfoPanel(
            task = task,
            resultCount = resultItems.size,
            isLoading = isLoading,
            searchInputText = searchInputText,
            useRegexInput = useRegexInput,
            searchErrorText = searchErrorText,
            messageTypeItems = messageTypeItems,
            messageTypeCounts = messageTypeCounts,
            selectedMessageTypes = selectedMessageTypes,
            canResetFilters = canResetFilters,
            onSearchInputChange = onSearchInputChange,
            onRegexInputChange = onRegexInputChange,
            onSearch = onSearch,
            onResetFilters = onResetFilters,
            onMessageTypeToggle = onMessageTypeToggle,
            onSelectAllMessageTypes = onSelectAllMessageTypes,
            modifier = Modifier
                .width(TaskResultTokens.infoPanelWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        )
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = TaskResultTokens.spacingMd),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            TaskResultListHeader(
                visibleCount = filteredResults.size,
                totalCount = resultItems.size,
                modifier = Modifier.fillMaxWidth(),
            )
            TaskResultGrid(
                resultItems = resultItems,
                filteredResults = filteredResults,
                searchText = appliedSearchText,
                useRegex = appliedUseRegex,
                isLoading = isLoading,
                onResetFilters = onResetFilters,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TaskResultInfoPanel(
    task: Task,
    resultCount: Int,
    isLoading: Boolean,
    searchInputText: String,
    useRegexInput: Boolean,
    searchErrorText: String,
    messageTypeItems: List<String>,
    messageTypeCounts: Map<String, Int>,
    selectedMessageTypes: Set<String>,
    canResetFilters: Boolean,
    onSearchInputChange: (String) -> Unit,
    onRegexInputChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onResetFilters: () -> Unit,
    onMessageTypeToggle: (String) -> Unit,
    onSelectAllMessageTypes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TaskResultSummary(
            task = task,
            resultCount = resultCount,
            isLoading = isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        TaskResultFilterSection(
            searchInputText = searchInputText,
            useRegexInput = useRegexInput,
            searchErrorText = searchErrorText,
            messageTypeItems = messageTypeItems,
            messageTypeCounts = messageTypeCounts,
            totalResultCount = resultCount,
            selectedMessageTypes = selectedMessageTypes,
            canReset = canResetFilters,
            onSearchInputChange = onSearchInputChange,
            onRegexInputChange = onRegexInputChange,
            onSearch = onSearch,
            onReset = onResetFilters,
            onMessageTypeToggle = onMessageTypeToggle,
            onSelectAllMessageTypes = onSelectAllMessageTypes,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TaskResultGrid(
    resultItems: List<TaskResultListItem>,
    filteredResults: List<TaskResultListItem>,
    searchText: String,
    useRegex: Boolean,
    isLoading: Boolean,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> TaskResultLoadingState(modifier = modifier)
        resultItems.isEmpty() -> ErrorEmptyData(
            message = AppStrings.task_results_empty,
            modifier = modifier,
        )
        filteredResults.isEmpty() -> ErrorEmptyData(
            message = AppStrings.task_results_no_matches,
            supportingText = AppStrings.task_results_adjust_filters,
            actionLabel = AppStrings.ui_reset,
            onAction = onResetFilters,
            modifier = modifier,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(TaskResultTokens.resultGridMinItemWidth),
            modifier = modifier,
            contentPadding = PaddingValues(
                bottom = TaskResultTokens.floatingActionButtonClearance,
            ),
            horizontalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            gridItems(
                items = filteredResults,
                key = TaskResultListItem::stableKey,
                contentType = { "result" },
            ) { item ->
                TaskResultItem(
                    item = item,
                    searchText = searchText,
                    useRegex = useRegex,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TaskResultSummary(
    task: Task,
    resultCount: Int,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskResultTokens.spacingSm),
        horizontalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconContainerColor = when (task.taskType) {
            TaskType.Copy -> MaterialTheme.colorScheme.primaryContainer
            TaskType.Move -> MaterialTheme.colorScheme.tertiaryContainer
            TaskType.Delete -> MaterialTheme.colorScheme.errorContainer
            TaskType.Download -> MaterialTheme.colorScheme.secondaryContainer
        }
        val iconContentColor = when (task.taskType) {
            TaskType.Copy -> MaterialTheme.colorScheme.onPrimaryContainer
            TaskType.Move -> MaterialTheme.colorScheme.onTertiaryContainer
            TaskType.Delete -> MaterialTheme.colorScheme.onErrorContainer
            TaskType.Download -> MaterialTheme.colorScheme.onSecondaryContainer
        }
        Surface(
            modifier = Modifier.size(TaskResultTokens.taskIconContainerSize),
            shape = MaterialTheme.shapes.large,
            color = iconContainerColor,
            contentColor = iconContentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(TaskResultTokens.taskIconSize)) {
                    task.ToIcon()
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingXs),
        ) {
            task.ToTitle(style = MaterialTheme.typography.titleLarge)
            task.values["path"]
                ?.takeIf(String::isNotBlank)
                ?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = AppStrings.ui_loading,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = AppStrings.ui_arg0_items.format(arg0 = resultCount.toString()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        task.ToStatusIcon()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskResultFilterSection(
    searchInputText: String,
    useRegexInput: Boolean,
    searchErrorText: String,
    messageTypeItems: List<String>,
    messageTypeCounts: Map<String, Int>,
    totalResultCount: Int,
    selectedMessageTypes: Set<String>,
    canReset: Boolean,
    onSearchInputChange: (String) -> Unit,
    onRegexInputChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit,
    onMessageTypeToggle: (String) -> Unit,
    onSelectAllMessageTypes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSearch = searchErrorText.isEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskResultTokens.spacingSm),
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TaskResultTokens.minimumTouchTarget),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = AppStrings.ui_search_filter,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (canReset) {
                TextButton(onClick = onReset) {
                    Text(AppStrings.ui_reset)
                }
            }
        }
        TaskResultSearchField(
            searchInputText = searchInputText,
            searchErrorText = searchErrorText,
            canSearch = canSearch,
            onSearchInputChange = onSearchInputChange,
            onSearch = onSearch,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
            verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
        ) {
            TaskResultRegexChip(
                selected = useRegexInput,
                onSelectedChange = onRegexInputChange,
            )
            FilterChip(
                selected = selectedMessageTypes.isEmpty(),
                onClick = onSelectAllMessageTypes,
                label = {
                    Text(
                        AppStrings.ui_all_arg0.format(
                            arg0 = totalResultCount.toString(),
                        ),
                    )
                },
            )
            messageTypeItems.forEach { type ->
                FilterChip(
                    selected = type in selectedMessageTypes,
                    onClick = { onMessageTypeToggle(type) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = {
                        Text(
                            text = "$type (${messageTypeCounts[type] ?: 0})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(
                                max = TaskResultTokens.messageFilterMaxWidth,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskResultSearchField(
    searchInputText: String,
    searchErrorText: String,
    canSearch: Boolean,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = searchInputText,
        onValueChange = onSearchInputChange,
        label = { Text(AppStrings.ui_search_path) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searchInputText.isNotEmpty()) {
                    IconButton(onClick = { onSearchInputChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = AppStrings.task_clear_action,
                        )
                    }
                }
                IconButton(
                    onClick = onSearch,
                    enabled = canSearch,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = AppStrings.ui_search,
                    )
                }
            }
        },
        supportingText = if (searchErrorText.isNotEmpty()) {
            { Text(searchErrorText) }
        } else {
            null
        },
        isError = searchErrorText.isNotEmpty(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    )
}

@Composable
private fun TaskResultRegexChip(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = { onSelectedChange(!selected) },
        label = { Text(AppStrings.ui_search_mode_regex) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun TaskResultListHeader(
    visibleCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = AppStrings.ui_task_results,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Text(
                text = AppStrings.task_results_showing_count.format(
                    arg0 = visibleCount.toString(),
                    arg1 = totalCount.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TaskResultLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskResultTokens.spacingLg),
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = AppStrings.ui_loading,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskResultItem(
    item: TaskResultListItem,
    searchText: String,
    useRegex: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            TaskResultCompactItemContent(
                item = item,
                searchText = searchText,
                useRegex = useRegex,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = TaskResultTokens.spacingSm),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TaskResultCompactItemContent(
    item: TaskResultListItem,
    searchText: String,
    useRegex: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
    ) {
        TaskResultPathContent(
            item = item,
            searchText = searchText,
            useRegex = useRegex,
        )
        TaskResultMessageContent(message = item.message)
    }
}

@Composable
private fun TaskResultPathContent(
    item: TaskResultListItem,
    searchText: String,
    useRegex: Boolean,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingXs),
    ) {
        if (showLabel) {
            Text(
                text = AppStrings.ui_path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            HighlightedText(
                text = item.path,
                keyword = searchText,
                useRegex = useRegex,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TaskResultMessageContent(
    message: String,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingXs),
    ) {
        if (showLabel) {
            Text(
                text = AppStrings.ui_error,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TaskResultTokens.spacingSm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview
@Composable
private fun TaskResultItemPreview() {
    MaterialTheme {
        TaskResultItem(
            item = TaskResultListItem(
                stableKey = "preview",
                path = "/Photos/2026/summer.jpg",
                message = "The destination already contains a file with the same name.",
            ),
            searchText = "2026",
            useRegex = false,
            modifier = Modifier.width(420.dp),
        )
    }
}
