package com.folderspan.ui.components.loading

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.folderspan.ui.components.status.StatusWidget
import com.folderspan.ui.components.status.StatusWidgetDefaults
import strings.AppStrings

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingBase(
    loadingText: String = AppStrings.ui_loading,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    StatusWidget(
        title = loadingText,
        supportingText = supportingText,
        modifier = modifier.pointerInput(Unit) {},
    ) {
        LoadingIndicator(
            modifier = Modifier.size(StatusWidgetDefaults.VisualSize),
        )
    }
}
