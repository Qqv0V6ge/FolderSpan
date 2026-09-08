package com.folderspan.pro.presentation.screen.profile

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.pro.core.ui.components.*
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.resolvePageViewState
import kotlinx.coroutines.launch

@Composable
fun PersonalSettingsPage(
    modifier: Modifier = Modifier,
    state: PersonalSettingsUiState,
    onNavigateBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onUseTarget: (SettingTargetViewData) -> Unit = {},
    onCancelUseTarget: () -> Unit = {},
    onCloneTarget: (SettingTargetViewData) -> Unit = {},
    onDeleteTarget: (SettingTargetViewData) -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarPrompt = proSnackbarPrompt(
        message = state.pageErrorMessage,
        tone = AuthStatusTone.Error,
    ) ?: proSnackbarPrompt(
        message = state.feedback?.text,
        tone = state.feedback?.tone ?: AuthStatusTone.Info,
    ) ?: if (state.isLoading && state.targets.isNotEmpty()) {
        proSnackbarPrompt(
            message = AppStrings.ui_updating_available_settings,
            tone = AuthStatusTone.Info,
        )
    } else {
        null
    }

    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Box(modifier = modifier.fillMaxSize()) {
        AccountEditorScaffold(
            title = AppStrings.ui_personalization,
            onNavigateBack = onNavigateBack,
            onLogout = onLogout
        ) { requestLogout ->
            ProfilePageStateLayout(
                state = resolvePageViewState(
                    isLoading = state.isLoading && state.targets.isEmpty(),
                    errorState = state.pageErrorMessage?.takeIf { state.targets.isEmpty() }?.let { message ->
                        PageErrorState(PageErrorType.General, message)
                    },
                ),
                refreshState = when {
                    state.isLoading && state.targets.isNotEmpty() -> PageRefreshState.Refreshing
                    state.pageErrorMessage != null && state.targets.isNotEmpty() ->
                        PageRefreshState.Error(state.pageErrorMessage)
                    else -> PageRefreshState.Idle
                },
                onRefresh = onRefresh,
                onRetry = onRefresh,
                isContentScrollable = false,
                loadingTitle = AppStrings.ui_looking_available_settings,
                error = {
                    UserProfileFallbackState(
                        title = AppStrings.ui_unable_read_personalized_settings_moment,
                        description = AppStrings.ui_there_currently_no_settings_display_please_try_again_later,
                        primaryLabel = AppStrings.ui_try_again,
                        onPrimary = onRefresh,
                        secondaryLabel = AppStrings.ui_log_out,
                        onSecondary = requestLogout,
                    )
                },
            ) {
                PersonalSettingsTargetGrid(
                    targets = state.targets,
                    activeRequestHeaderDeviceKey = state.activeRequestHeaderDeviceKey,
                    isActionSubmitting = state.isTargetActionSubmitting,
                    onUseTargetRequest = onUseTarget,
                    onCancelUseTargetRequest = onCancelUseTarget,
                    onCopyTargetRequest = { target ->
                        scope.launch {
                            val result = snackbarHostState.showProSnackbar(
                                message = AppStrings.ui_copy_arg0_this_device.format(arg0 = target.name),
                                tone = AuthStatusTone.Info,
                                actionLabel = AppStrings.ui_confirm,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onCloneTarget(target)
                            }
                        }
                    },
                    onDeleteTargetRequest = { target ->
                        scope.launch {
                            val result = snackbarHostState.showProSnackbar(
                                message = AppStrings.ui_delete_arg0.format(arg0 = target.name),
                                tone = AuthStatusTone.Error,
                                actionLabel = AppStrings.ui_confirm,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onDeleteTarget(target)
                            }
                        }
                    },
                )
            }
        }

        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
