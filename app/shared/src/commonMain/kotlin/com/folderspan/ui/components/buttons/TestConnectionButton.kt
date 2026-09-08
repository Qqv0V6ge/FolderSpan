package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TestConnectionButton(
    isTesting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.tertiary,
    contentColor: Color = MaterialTheme.colorScheme.onTertiary,
    idleText: String = AppStrings.ui_test_connection,
    testingText: String = AppStrings.ui_testing,
) {
    val contentState = testConnectionButtonContentState(
        isTesting = isTesting,
        idleText = idleText,
        testingText = testingText
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        icon = {
            if (contentState.showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp).size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Link, contentDescription = null)
            }
        },
        text = { Text(contentState.text) },
    )
}

@Immutable
internal data class TestConnectionButtonContentState(
    val text: String,
    val showProgress: Boolean,
)

internal fun testConnectionButtonContentState(
    isTesting: Boolean,
    idleText: String = AppStrings.ui_test_connection,
    testingText: String = AppStrings.ui_testing,
): TestConnectionButtonContentState {
    return if (isTesting) {
        TestConnectionButtonContentState(
            text = testingText,
            showProgress = true
        )
    } else {
        TestConnectionButtonContentState(
            text = idleText,
            showProgress = false
        )
    }
}
