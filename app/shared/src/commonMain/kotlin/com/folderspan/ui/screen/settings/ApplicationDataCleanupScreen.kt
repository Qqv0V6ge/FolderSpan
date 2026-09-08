package com.folderspan.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folderspan.cleanup.ApplicationDataCleanupCategory
import com.folderspan.cleanup.ApplicationDataCleanupState
import com.folderspan.crash.exitApp
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings

/**
 * 开发者设置 -> 清理软件数据（二级页面）。
 */
class ApplicationDataCleanupScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val cleanupState = koinInject<ApplicationDataCleanupState>()
        val availableCategories = remember(cleanupState) { cleanupState.availableCategories }
        val cleanupSupported = remember(availableCategories) { availableCategories.isNotEmpty() }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var encodedSelection by rememberSaveable { mutableStateOf("") }
        var showConfirmation by rememberSaveable { mutableStateOf(false) }
        var cleanupRequested by remember { mutableStateOf(false) }
        val selectedCategories = remember(encodedSelection, availableCategories) {
            decodeSelectedCategories(encodedSelection).intersect(availableCategories)
        }

        AppScaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.developer_cleanup_application_data_title) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
            bottomBar = {
                if (cleanupSupported) {
                    Surface(shadowElevation = 3.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = AppStrings.ui_arg0_items_selected.format(
                                    arg0 = selectedCategories.size.toString()
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showConfirmation = true },
                                enabled = selectedCategories.isNotEmpty() && !cleanupRequested,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(AppStrings.developer_cleanup_application_data_action)
                            }
                        }
                    }
                }
            },
        ) { padding ->
            if (cleanupSupported) {
                ApplicationDataCleanupOptions(
                    selectedCategories = selectedCategories,
                    onCategorySelected = { category, selected ->
                        encodedSelection = encodeSelectedCategories(
                            if (selected) {
                                selectedCategories + category
                            } else {
                                selectedCategories - category
                            }
                        )
                    },
                    onSelectAll = { selected ->
                        encodedSelection = encodeSelectedCategories(
                            if (selected) availableCategories else emptySet()
                        )
                    },
                    availableCategories = availableCategories,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else {
                Text(
                    text = AppStrings.developer_cleanup_application_data_unsupported,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }

        if (showConfirmation) {
            AlertDialog(
                onDismissRequest = { showConfirmation = false },
                title = { Text(AppStrings.developer_cleanup_application_data_confirm_title) },
                text = {
                    Column {
                        Text(AppStrings.developer_cleanup_application_data_confirm_message)
                        Spacer(modifier = Modifier.height(12.dp))
                        selectedCategories.forEach { category ->
                            Text("• ${category.title()}")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmation = false
                            cleanupRequested = true
                            scope.launch {
                                cleanupState.requestCleanup(selectedCategories).fold(
                                    onSuccess = { exitApp() },
                                    onFailure = {
                                        cleanupRequested = false
                                        snackbarHostState.showSnackbar(
                                            AppStrings.developer_cleanup_application_data_failed
                                        )
                                    },
                                )
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text(AppStrings.developer_cleanup_application_data_confirm_action)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text(AppStrings.ui_cancel)
                    }
                },
            )
        }
    }
}

@Composable
internal fun ApplicationDataCleanupOptions(
    availableCategories: Set<ApplicationDataCleanupCategory>,
    selectedCategories: Set<ApplicationDataCleanupCategory>,
    onCategorySelected: (ApplicationDataCleanupCategory, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = availableCategories.isNotEmpty() &&
        selectedCategories.containsAll(availableCategories)

    GridList(modifier = modifier) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = AppStrings.developer_cleanup_application_data_screen_description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ListItem(
                headlineContent = {
                    Text(if (allSelected) AppStrings.ui_deselect_all else AppStrings.ui_select_all)
                },
                trailingContent = {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cleanup-select-all")
                    .toggleable(
                        value = allSelected,
                        role = Role.Checkbox,
                        onValueChange = onSelectAll,
                    ),
            )
        }

        ApplicationDataCleanupCategory.entries
            .filter(availableCategories::contains)
            .forEach { category ->
            item(
                key = category.name,
                span = { GridItemSpan(maxLineSpan) },
            ) {
                val selected = category in selectedCategories
                ListItem(
                    headlineContent = { Text(category.title()) },
                    supportingContent = { Text(category.description()) },
                    trailingContent = {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cleanup-category-${category.name}")
                        .toggleable(
                            value = selected,
                            role = Role.Checkbox,
                            onValueChange = { checked -> onCategorySelected(category, checked) },
                        ),
                )
            }
        }
    }
}

private fun encodeSelectedCategories(categories: Set<ApplicationDataCleanupCategory>): String =
    ApplicationDataCleanupCategory.entries
        .filter(categories::contains)
        .joinToString(",", transform = ApplicationDataCleanupCategory::name)

private fun decodeSelectedCategories(encoded: String): Set<ApplicationDataCleanupCategory> {
    if (encoded.isBlank()) return emptySet()
    val categoriesByName = ApplicationDataCleanupCategory.entries.associateBy { category -> category.name }
    return encoded
        .split(',')
        .mapNotNullTo(linkedSetOf()) { name -> categoriesByName[name] }
}

private fun ApplicationDataCleanupCategory.title(): String = when (this) {
    ApplicationDataCleanupCategory.ApplicationData ->
        AppStrings.developer_cleanup_application_data_category_application_data_title

    ApplicationDataCleanupCategory.PreferencesAndAccount ->
        AppStrings.developer_cleanup_application_data_category_preferences_title

    ApplicationDataCleanupCategory.TransferHistory ->
        AppStrings.developer_cleanup_application_data_category_history_title

    ApplicationDataCleanupCategory.CacheAndLogs ->
        AppStrings.developer_cleanup_application_data_category_cache_title

    ApplicationDataCleanupCategory.LoginStartup ->
        AppStrings.developer_cleanup_application_data_category_startup_title
}

private fun ApplicationDataCleanupCategory.description(): String = when (this) {
    ApplicationDataCleanupCategory.ApplicationData ->
        AppStrings.developer_cleanup_application_data_category_application_data_description

    ApplicationDataCleanupCategory.PreferencesAndAccount ->
        AppStrings.developer_cleanup_application_data_category_preferences_description

    ApplicationDataCleanupCategory.TransferHistory ->
        AppStrings.developer_cleanup_application_data_category_history_description

    ApplicationDataCleanupCategory.CacheAndLogs ->
        AppStrings.developer_cleanup_application_data_category_cache_description

    ApplicationDataCleanupCategory.LoginStartup ->
        AppStrings.developer_cleanup_application_data_category_startup_description
}
