package com.folderspan.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.localization.AppLanguageMode
import com.folderspan.localization.AppText
import com.folderspan.ui.components.grid.GridList
import strings.AppStrings

@Composable
internal fun LanguageOptions(
    selectedMode: AppLanguageMode,
    onModeSelected: (AppLanguageMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    GridList(modifier = modifier) {
        languageOptions(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        )
    }
}

internal fun LazyGridScope.languageOptions(
    selectedMode: AppLanguageMode,
    onModeSelected: (AppLanguageMode) -> Unit,
) {
    item(
        key = "language-description",
        span = { GridItemSpan(maxLineSpan) },
    ) {
        Text(
            text = AppStrings.settings_language_screen_description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    listOf(
        AppLanguageMode.System,
        AppLanguageMode.SimplifiedChinese,
        AppLanguageMode.English,
    ).forEach { mode ->
        item(
            key = "language-${mode.name}",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            val selected = mode == selectedMode
            ListItem(
                headlineContent = { Text(AppText.languageTitle(mode)) },
                supportingContent = { Text(AppText.languageDescription(mode)) },
                trailingContent = {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("language-option-${mode.name}")
                    .semantics {
                        this.selected = selected
                    }
                    .clickable(
                        role = Role.RadioButton,
                        onClick = { onModeSelected(mode) },
                    ),
            )
        }
    }
}
