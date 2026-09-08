package com.folderspan.pro.presentation.screen.profile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.extensions.DeviceIcon
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt
import com.folderspan.pro.core.ui.components.showProSnackbar
import com.folderspan.pro.domain.model.MAX_PROFILE_AVATAR_BYTES
import com.folderspan.ui.components.error.ErrorBase
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.loading.LoadingBase
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.seiko.imageloader.rememberImagePainter
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import strings.AppStrings
import kotlin.time.Instant

private fun SectionFeedback?.toProSnackbarPrompt() =
    proSnackbarPrompt(
        message = this?.text,
        tone = this?.tone ?: AuthStatusTone.Info,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfilePage(
    modifier: Modifier = Modifier,
    state: UserProfileUiState,
    onNavigateBack: () -> Unit = {},
    onNavigateToLoginDevices: () -> Unit = {},
    onNavigateToEditProfile: () -> Unit = {},
    onNavigateToChangePassword: () -> Unit = {},
    onNavigateToPersonalSettings: () -> Unit = {},
    onRefreshAll: () -> Unit = {},
    onWithdrawProfileReview: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    var isLogoutConfirmationVisible by remember { mutableStateOf(false) }
    var isWithdrawConfirmationVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarPrompt = proSnackbarPrompt(
        message = state.pageErrorMessage,
        tone = AuthStatusTone.Error,
    ) ?: state.profileFeedback.toProSnackbarPrompt()

    val requestLogout = { isLogoutConfirmationVisible = true }
    val confirmLogout: () -> Unit = {
        isLogoutConfirmationVisible = false
        onLogout()
    }

    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = {
            ProSnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppStrings.ui_return
                        )
                    }
                },
                title = {
                    Text(
                        text = AppStrings.ui_my_account,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = requestLogout) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = AppStrings.ui_log_out
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            val layoutMode = resolveUserProfileLayoutMode(DpSize(maxWidth, maxHeight))
            val verticalPadding = when (layoutMode) {
                UserProfileLayoutMode.Compact -> 20.dp
                UserProfileLayoutMode.Medium -> 28.dp
                UserProfileLayoutMode.Expanded -> 34.dp
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = verticalPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                UserProfileContent(
                    modifier = Modifier.fillMaxSize(),
                    layoutMode = layoutMode,
                    profile = state.profile,
                    hasCheckedProfileCache = state.hasCheckedProfileCache,
                    isRefreshingPage = state.isRefreshingPage,
                    isWithdrawingProfileReview = state.isWithdrawingProfileReview,
                    onRefreshAll = onRefreshAll,
                    onWithdrawProfileReview = { isWithdrawConfirmationVisible = true },
                    onNavigateToLoginDevices = onNavigateToLoginDevices,
                    onNavigateToEditProfile = onNavigateToEditProfile,
                    onNavigateToChangePassword = onNavigateToChangePassword,
                    onNavigateToPersonalSettings = onNavigateToPersonalSettings,
                    onLogout = requestLogout
                )
            }

            if (isLogoutConfirmationVisible) {
                LogoutConfirmationDialog(
                    onDismiss = { isLogoutConfirmationVisible = false },
                    onConfirm = confirmLogout
                )
            }

            if (isWithdrawConfirmationVisible) {
                WithdrawProfileReviewConfirmationDialog(
                    onDismiss = { isWithdrawConfirmationVisible = false },
                    onConfirm = {
                        isWithdrawConfirmationVisible = false
                        onWithdrawProfileReview()
                    },
                )
            }
        }
    }
}

@Composable
fun LoginDevicesPage(
    modifier: Modifier = Modifier,
    state: UserProfileUiState,
    currentDeviceKey: String,
    onNavigateBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRemoveDevice: (Long, Boolean) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarPrompt = proSnackbarPrompt(
        message = state.pageErrorMessage,
        tone = AuthStatusTone.Error,
    ) ?: state.devicesFeedback.toProSnackbarPrompt()
        ?: if (state.isRefreshingPage && state.devices.isNotEmpty()) {
            proSnackbarPrompt(
                message = AppStrings.ui_updating_login_device,
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
            modifier = Modifier.fillMaxSize(),
            title = AppStrings.ui_device_management,
            onNavigateBack = onNavigateBack,
            onLogout = onLogout
        ) { requestLogout ->
            val devicesMode = resolveLoginDevicesContentMode(
                hasDevices = state.devices.isNotEmpty(),
                isRefreshingPage = state.isRefreshingPage,
                hasPageError = state.pageErrorMessage != null,
            )
            ProfilePageStateLayout(
                state = when (devicesMode) {
                    LoginDevicesContentMode.Loading -> PageViewState.Loading
                    LoginDevicesContentMode.Fallback -> PageViewState.Error(
                        PageErrorState(PageErrorType.General),
                    )
                    LoginDevicesContentMode.Content -> PageViewState.Content
                },
                refreshState = if (state.isRefreshingPage && state.devices.isNotEmpty()) {
                    PageRefreshState.Refreshing
                } else {
                    PageRefreshState.Idle
                },
                onRefresh = onRefresh,
                onRetry = onRefresh,
                isContentScrollable = false,
                loadingTitle = AppStrings.ui_reading_login_device,
                error = {
                    UserProfileFallbackState(
                        title = AppStrings.ui_login_device_cannot_read_moment,
                        description = AppStrings.ui_there_currently_no_device_record_available_display_please_try,
                        primaryLabel = AppStrings.ui_reload,
                        onPrimary = onRefresh,
                        secondaryLabel = AppStrings.ui_log_out,
                        onSecondary = requestLogout,
                    )
                },
            ) {
                DeviceGrid(
                    devices = state.devices,
                    currentDeviceKey = currentDeviceKey,
                    removingDeviceIds = state.removingDeviceIds,
                    onRemoveDeviceRequest = { device ->
                        scope.launch {
                            val result = snackbarHostState.showProSnackbar(
                                message = AppStrings.ui_delete_arg0.format(arg0 = device.deviceName),
                                tone = AuthStatusTone.Error,
                                actionLabel = AppStrings.ui_confirm,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onRemoveDevice(device.id, device.deviceKey == currentDeviceKey)
                            }
                        }
                    },
                )
            }
        }

        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountEditorScaffold(
    modifier: Modifier = Modifier,
    title: String,
    isFormPage: Boolean = false,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    content: @Composable (() -> Unit) -> Unit,
) {
    var isLogoutConfirmationVisible by remember { mutableStateOf(false) }
    val requestLogout = { isLogoutConfirmationVisible = true }
    val confirmLogout: () -> Unit = {
        isLogoutConfirmationVisible = false
        onLogout()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppStrings.ui_return
                        )
                    }
                },
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = requestLogout) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = AppStrings.ui_log_out
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopCenter,
        ) {
            val layoutMode = resolveUserProfileLayoutMode(DpSize(maxWidth, maxHeight))
            val horizontalPadding = if (isFormPage) {
                when (layoutMode) {
                    UserProfileLayoutMode.Compact -> 20.dp
                    UserProfileLayoutMode.Medium -> 28.dp
                    UserProfileLayoutMode.Expanded -> 40.dp
                }
            } else {
                0.dp
            }
            val verticalPadding = when (layoutMode) {
                UserProfileLayoutMode.Compact -> 20.dp
                UserProfileLayoutMode.Medium -> 28.dp
                UserProfileLayoutMode.Expanded -> 34.dp
            }

            Box(
                modifier = Modifier
                    .then(if (isFormPage) Modifier.widthIn(max = 760.dp) else Modifier)
                    .fillMaxSize()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = verticalPadding,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                content(requestLogout)
            }

            if (isLogoutConfirmationVisible) {
                LogoutConfirmationDialog(
                    onDismiss = { isLogoutConfirmationVisible = false },
                    onConfirm = confirmLogout
                )
            }
        }
    }
}

@Composable
internal fun EditorPageContent(
    isLoading: Boolean,
    hasContent: Boolean,
    loadingTitle: String,
    fallbackTitle: String,
    fallbackDescription: String,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: (() -> Unit)? = onRetry,
    content: @Composable () -> Unit,
) {
    ProfilePageStateLayout(
        state = resolvePageViewState(
            isLoading = isLoading && !hasContent,
            errorState = if (!hasContent && !isLoading) {
                PageErrorState(PageErrorType.General)
            } else {
                null
            },
        ),
        refreshState = if (isLoading && hasContent) {
            PageRefreshState.Refreshing
        } else {
            PageRefreshState.Idle
        },
        onRefresh = onRefresh,
        onRetry = onRetry,
        loadingTitle = loadingTitle,
        error = {
            UserProfileFallbackState(
                title = fallbackTitle,
                description = fallbackDescription,
                primaryLabel = AppStrings.ui_reload,
                onPrimary = onRetry,
                secondaryLabel = AppStrings.ui_log_out,
                onSecondary = onLogout,
            )
        },
        content = content,
    )
}

@Composable
internal fun ProfilePageStateLayout(
    state: PageViewState,
    modifier: Modifier = Modifier,
    refreshState: PageRefreshState = PageRefreshState.Idle,
    onRefresh: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    isContentScrollable: Boolean = true,
    loadingTitle: String,
    error: @Composable (PageErrorState) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        PageStateLayout(
            state = state,
            modifier = Modifier.fillMaxSize(),
            refreshState = refreshState,
            onRefresh = onRefresh,
            onRetry = onRetry,
            onRetryRefresh = onRefresh,
            fillMaxSize = true,
            loading = {
                LoadingBase(
                    loadingText = loadingTitle,
                )
            },
            error = error,
        ) {
            if (isContentScrollable) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

@Composable
private fun UserProfileContent(
    modifier: Modifier,
    layoutMode: UserProfileLayoutMode,
    profile: UserProfileViewData?,
    hasCheckedProfileCache: Boolean,
    isRefreshingPage: Boolean,
    isWithdrawingProfileReview: Boolean,
    onRefreshAll: () -> Unit,
    onWithdrawProfileReview: () -> Unit,
    onNavigateToLoginDevices: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToPersonalSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val contentMode = resolveUserProfileContentMode(
        profile != null,
        hasCheckedProfileCache,
        isRefreshingPage,
    )
    if (contentMode == UserProfileContentMode.WaitingForCache) {
        Box(modifier)
    } else {
        ProfilePageStateLayout(
            state = when (contentMode) {
                UserProfileContentMode.Requesting -> PageViewState.Loading
                UserProfileContentMode.Fallback -> PageViewState.Error(PageErrorState(PageErrorType.General))
                UserProfileContentMode.WaitingForCache,
                UserProfileContentMode.Content -> PageViewState.Content
            },
            modifier = modifier,
            refreshState = if (isRefreshingPage && profile != null) {
                PageRefreshState.Refreshing
            } else {
                PageRefreshState.Idle
            },
            onRefresh = onRefreshAll,
            onRetry = onRefreshAll,
            loadingTitle = AppStrings.ui_reading_data,
            error = {
                UserProfileFallbackState(
                    title = AppStrings.ui_unable_read_data_moment,
                    description = AppStrings.ui_there_currently_no_data_display_please_try_again_later,
                    primaryLabel = AppStrings.ui_reload,
                    onPrimary = onRefreshAll,
                    secondaryLabel = AppStrings.ui_log_out,
                    onSecondary = onLogout,
                )
            },
        ) {
            profile?.let { currentProfile ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    UserProfileHeroCard(
                        profile = currentProfile,
                        layoutMode = layoutMode,
                        isWithdrawingProfileReview = isWithdrawingProfileReview,
                        onNavigateToEditProfile = onNavigateToEditProfile,
                        onWithdrawProfileReview = onWithdrawProfileReview,
                    )
                    UserProfileActionCard(
                        onNavigateToLoginDevices = onNavigateToLoginDevices,
                        onNavigateToChangePassword = onNavigateToChangePassword,
                        onNavigateToPersonalSettings = onNavigateToPersonalSettings,
                        onLogout = onLogout,
                    )
                }
            }
        }
    }
}

@Composable
private fun WithdrawProfileReviewConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_profile_review_withdraw_confirm_title) },
        text = {
            Text(
                text = AppStrings.ui_profile_review_withdraw_confirm_message,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(AppStrings.ui_profile_review_withdraw_confirm_action)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        },
    )
}

@Composable
private fun LogoutConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_confirm_log_out) },
        text = {
            Text(
                text = AppStrings.ui_after_logging_out_you_need_log_again_continue_accessing,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(AppStrings.ui_confirm_exit)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
internal fun UserProfileHeroCard(
    profile: UserProfileViewData,
    layoutMode: UserProfileLayoutMode,
    isWithdrawingProfileReview: Boolean = false,
    onNavigateToEditProfile: (() -> Unit)? = null,
    onWithdrawProfileReview: (() -> Unit)? = null,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(
                        avatarUrl = profile.avatar,
                        label = profile.initials(),
                        size = if (layoutMode == UserProfileLayoutMode.Expanded) 96.dp else 82.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = if (layoutMode == UserProfileLayoutMode.Expanded) {
                                MaterialTheme.typography.headlineLarge
                            } else {
                                MaterialTheme.typography.headlineMedium
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = profile.email,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onNavigateToEditProfile != null) {
                        IconButton(onClick = onNavigateToEditProfile) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = AppStrings.ui_enter_data_editing_page,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (!profile.profileEditable) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = AppStrings.ui_profile_review_pending_lock,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (onWithdrawProfileReview != null) {
                                TextButton(
                                    onClick = onWithdrawProfileReview,
                                    enabled = !isWithdrawingProfileReview,
                                ) {
                                    Text(
                                        if (isWithdrawingProfileReview) {
                                            AppStrings.ui_profile_review_withdrawing
                                        } else {
                                            AppStrings.ui_profile_review_withdraw
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditorCard(
    modifier: Modifier = Modifier,
    isSubmitting: Boolean,
    isAvatarSubmitting: Boolean,
    profile: UserProfileViewData,
    name: String,
    signature: String,
    avatarFeedback: SectionFeedback?,
    canRetryAvatarAction: Boolean,
    nameFieldError: String?,
    onNameChange: (String) -> Unit,
    onAvatarPictureOutcome: (AvatarPictureOutcome) -> Unit,
    onRequestAvatarRemoval: () -> Unit,
    onRetryAvatarAction: () -> Unit,
    onSignatureChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    SettingsSectionCard(modifier = modifier) {
        if (!profile.profileEditable) {
            Text(
                text = AppStrings.ui_profile_review_pending_lock,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(
                    avatarUrl = profile.avatar,
                    label = profile.initials(),
                    size = 72.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isAvatarSubmitting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            text = AppStrings.ui_profile_avatar_processing,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarPictureAction(
                            maxOutputBytes = MAX_PROFILE_AVATAR_BYTES,
                            onOutcome = onAvatarPictureOutcome,
                            enabled = profile.profileEditable && !isAvatarSubmitting && !isSubmitting,
                        ) {
                            Text(
                                if (profile.avatar.isNullOrBlank()) {
                                    AppStrings.ui_profile_avatar_choose
                                } else {
                                    AppStrings.ui_profile_avatar_replace
                                },
                            )
                        }
                        if (!profile.avatar.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onRequestAvatarRemoval,
                                enabled = profile.profileEditable && !isAvatarSubmitting && !isSubmitting,
                            ) {
                                Text(AppStrings.ui_profile_avatar_remove)
                            }
                        }
                    }
                    if (LocalAvatarPicturePicker.current == null) {
                        Text(
                            text = AppStrings.ui_profile_avatar_unavailable,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            avatarFeedback?.let { feedback ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = feedback.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (feedback.tone == AuthStatusTone.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    if (canRetryAvatarAction) {
                        TextButton(
                            onClick = onRetryAvatarAction,
                            enabled = profile.profileEditable && !isAvatarSubmitting && !isSubmitting,
                        ) { Text(AppStrings.ui_profile_avatar_retry) }
                    }
                }
            }
        }

        SettingsTextField(
            label = AppStrings.ui_nickname,
            value = name,
            onValueChange = onNameChange,
            placeholder = AppStrings.ui_enter_new_nickname,
            leadingIcon = Icons.Filled.Edit,
            errorMessage = nameFieldError,
            enabled = profile.profileEditable && !isSubmitting && !isAvatarSubmitting
        )

        SettingsTextField(
            label = AppStrings.ui_personalized_signature,
            value = signature,
            onValueChange = onSignatureChange,
            placeholder = AppStrings.ui_write_sentence_that_you_want_show,
            leadingIcon = Icons.Filled.Edit,
            enabled = profile.profileEditable && !isSubmitting && !isAvatarSubmitting,
            singleLine = false,
            minLines = 3
        )

        Button(
            onClick = onSubmit,
            enabled = profile.profileEditable && !isSubmitting && !isAvatarSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Text(if (isSubmitting) AppStrings.ui_saving else AppStrings.ui_save_data)
        }
    }
}

@Composable
internal fun PasswordEditorCard(
    modifier: Modifier = Modifier,
    isSubmitting: Boolean,
    oldPassword: String,
    newPassword: String,
    confirmPassword: String,
    oldPasswordVisible: Boolean,
    newPasswordVisible: Boolean,
    confirmPasswordVisible: Boolean,
    oldPasswordFieldError: String?,
    newPasswordFieldError: String?,
    confirmPasswordFieldError: String?,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onOldPasswordVisibilityToggle: () -> Unit,
    onNewPasswordVisibilityToggle: () -> Unit,
    onConfirmPasswordVisibilityToggle: () -> Unit,
    onSubmit: () -> Unit,
) {
    SettingsSectionCard(modifier = modifier) {
        SettingsPasswordField(
            label = AppStrings.ui_old_password,
            value = oldPassword,
            onValueChange = onOldPasswordChange,
            placeholder = AppStrings.ui_enter_current_password,
            visible = oldPasswordVisible,
            onVisibilityToggle = onOldPasswordVisibilityToggle,
            errorMessage = oldPasswordFieldError,
            enabled = !isSubmitting
        )

        SettingsPasswordField(
            label = AppStrings.ui_new_password,
            value = newPassword,
            onValueChange = onNewPasswordChange,
            placeholder = AppStrings.ui_least_8_digits,
            visible = newPasswordVisible,
            onVisibilityToggle = onNewPasswordVisibilityToggle,
            errorMessage = newPasswordFieldError,
            enabled = !isSubmitting
        )

        SettingsPasswordField(
            label = AppStrings.ui_confirm_new_password,
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
            placeholder = AppStrings.ui_enter_new_password_again,
            visible = confirmPasswordVisible,
            onVisibilityToggle = onConfirmPasswordVisibilityToggle,
            errorMessage = confirmPasswordFieldError,
            enabled = !isSubmitting
        )

        Button(
            onClick = onSubmit,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Text(if (isSubmitting) AppStrings.ui_updating else AppStrings.ui_update_password)
        }
    }
}

@Composable
private fun DeviceGrid(
    devices: List<UserDeviceViewData>,
    currentDeviceKey: String,
    removingDeviceIds: Set<Long>,
    onRemoveDeviceRequest: (UserDeviceViewData) -> Unit,
) {
    GridList(
        modifier = Modifier.fillMaxSize(),
        isEmpty = devices.isEmpty(),
        emptyMessage = AppStrings.ui_there_currently_no_device_records_display,
        verticalSpacing = 12.dp,
        horizontalSpacing = 12.dp,
    ) {
        items(
            items = devices,
            key = { it.id },
            contentType = { "login-device" },
        ) { device ->
            DeviceRowCard(
                device = device,
                isCurrentDevice = device.deviceKey == currentDeviceKey,
                isRemoving = device.id in removingDeviceIds,
                onRemove = { onRemoveDeviceRequest(device) }
            )
        }
    }
}

@Composable
private fun DeviceRowCard(
    device: UserDeviceViewData,
    isCurrentDevice: Boolean,
    isRemoving: Boolean,
    onRemove: () -> Unit,
) {
    val actionContentDescription = if (isRemoving) AppStrings.ui_removing else AppStrings.ui_remove

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge
            ),
        leadingContent = {
            ProfileListLeadingSlot {
                PlatformType.DeviceIcon(
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.deviceName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentDevice) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.large
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = AppStrings.ui_current_device,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        supportingContent = {
            Text(
                text = loginDeviceSupportingText(device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            ProfileListTrailingAction(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = actionContentDescription,
                enabled = !isRemoving,
                onClick = onRemove
            )
        }
    )
}

@Composable
private fun UserProfileActionCard(
    onNavigateToLoginDevices: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToPersonalSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    SettingsSectionCard {
        QuickActionListItem(
            icon = Icons.Filled.Devices,
            title = AppStrings.ui_log_into_device,
            subtitle = AppStrings.ui_view_manage_devices_logged_into_current_account,
            contentDescription = AppStrings.ui_enter_login_device_page,
            onClick = onNavigateToLoginDevices
        )
        QuickActionListItem(
            icon = Icons.Filled.Lock,
            title = AppStrings.ui_password_management,
            subtitle = AppStrings.ui_enter_independent_page_update_login_password,
            contentDescription = AppStrings.ui_enter_password_change_page,
            onClick = onNavigateToChangePassword
        )
        QuickActionListItem(
            icon = Icons.Filled.Settings,
            title = AppStrings.ui_preferences,
            subtitle = AppStrings.ui_copy_preferences_other_devices_this_device,
            contentDescription = AppStrings.ui_enter_personalization_page,
            onClick = onNavigateToPersonalSettings
        )
        QuickActionListItem(
            icon = Icons.AutoMirrored.Filled.Logout,
            title = AppStrings.ui_log_out,
            subtitle = AppStrings.ui_you_need_log_again_after_logging_out,
            contentDescription = AppStrings.ui_log_out,
            onClick = onLogout
        )
    }
}

@Composable
private fun QuickActionListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge
            ),
        leadingContent = {
            ProfileListLeadingSlot {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            ProfileListTrailingAction(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = contentDescription
            )
        }
    )
}

@Composable
internal fun PersonalSettingsTargetGrid(
    targets: List<SettingTargetViewData>,
    activeRequestHeaderDeviceKey: String? = null,
    isActionSubmitting: Boolean = false,
    onUseTargetRequest: (SettingTargetViewData) -> Unit = {},
    onCancelUseTargetRequest: () -> Unit = {},
    onCopyTargetRequest: (SettingTargetViewData) -> Unit = {},
    onDeleteTargetRequest: (SettingTargetViewData) -> Unit = {},
) {
    GridList(
        modifier = Modifier.fillMaxSize(),
        isEmpty = targets.isEmpty(),
        emptyMessage = AppStrings.ui_there_currently_no_settings_copy,
        verticalSpacing = 12.dp,
        horizontalSpacing = 12.dp,
    ) {
        items(
            items = targets,
            key = { it.id },
            contentType = { "personal-setting-target" },
        ) { target ->
            PersonalSettingsTargetRow(
                target = target,
                isTargetInUse = target.isUsingRequestHeader(activeRequestHeaderDeviceKey),
                actionsEnabled = !isActionSubmitting,
                onUse = { onUseTargetRequest(target) },
                onCancelUse = onCancelUseTargetRequest,
                onCopy = { onCopyTargetRequest(target) },
                onDelete = { onDeleteTargetRequest(target) }
            )
        }
    }
}

@Composable
private fun PersonalSettingsTargetRow(
    target: SettingTargetViewData,
    isTargetInUse: Boolean = false,
    actionsEnabled: Boolean = true,
    onUse: () -> Unit = {},
    onCancelUse: () -> Unit = {},
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge
            ),
        leadingContent = {
            ProfileListLeadingSlot {
                target.subType.toSettingTargetDeviceType().DeviceIcon(
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = target.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                settingTargetSupportingText(target)?.let { supportingText ->
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                target.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        trailingContent = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { isMenuExpanded = true },
                    enabled = actionsEnabled
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = AppStrings.ui_more_actions
                    )
                }
                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false }
                ) {
                    if (isTargetInUse) {
                        DropdownMenuItem(
                            text = { Text(PersonalSettingsTargetMenuLabelCancelUse) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.LinkOff,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                isMenuExpanded = false
                                onCancelUse()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(PersonalSettingsTargetMenuLabelUse) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                isMenuExpanded = false
                                onUse()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(PersonalSettingsTargetMenuLabelCopy) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onCopy()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(PersonalSettingsTargetMenuLabelDelete) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    )
}

internal enum class UserProfileContentMode {
    WaitingForCache,
    Requesting,
    Fallback,
    Content,
}

internal fun resolveUserProfileContentMode(
    hasProfile: Boolean,
    hasCheckedProfileCache: Boolean,
    isRefreshingPage: Boolean,
): UserProfileContentMode =
    when {
        hasProfile -> UserProfileContentMode.Content
        !hasCheckedProfileCache -> UserProfileContentMode.WaitingForCache
        isRefreshingPage -> UserProfileContentMode.Requesting
        else -> UserProfileContentMode.Fallback
    }

internal enum class LoginDevicesContentMode {
    Loading,
    Fallback,
    Content,
}

internal fun resolveLoginDevicesContentMode(
    hasDevices: Boolean,
    isRefreshingPage: Boolean,
    hasPageError: Boolean,
): LoginDevicesContentMode =
    when {
        hasDevices -> LoginDevicesContentMode.Content
        isRefreshingPage -> LoginDevicesContentMode.Loading
        hasPageError -> LoginDevicesContentMode.Fallback
        else -> LoginDevicesContentMode.Content
    }

@Composable
private fun ProfileListLeadingSlot(
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun ProfileListTrailingAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (onClick == null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription
                )
            }
        }
    }
}

@Composable
internal fun UserProfileLoadingState(
    title: String = AppStrings.ui_loading,
) {
    LoadingBase(
        loadingText = title,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UserProfileFallbackState(
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorBase(
            text = title,
            imageVector = Icons.Filled.ErrorOutline,
            supportingText = description,
            actionLabel = primaryLabel,
            onAction = onPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Text(secondaryLabel)
        }
    }
}

@Composable
private fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = errorMessage != null,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        supportingText = errorMessage?.let { message ->
            {
                Text(message)
            }
        },
        singleLine = singleLine,
        minLines = minLines,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun SettingsPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onVisibilityToggle: () -> Unit,
    errorMessage: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = errorMessage != null,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onVisibilityToggle,
                enabled = enabled
            ) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) AppStrings.ui_hide_password else AppStrings.ui_show_password,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        supportingText = errorMessage?.let { message ->
            {
                Text(message)
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    )
}

@Composable
private fun ProfileAvatar(
    avatarUrl: String?,
    label: String,
    size: Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary
        )
        if (!avatarUrl.isNullOrBlank()) {
            Image(
                painter = rememberImagePainter(avatarUrl),
                contentDescription = AppStrings.ui_user_avatar,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
        }
    }
}

internal fun resolveUserProfileLayoutMode(windowWidthSizeClass: WindowWidthSizeClass): UserProfileLayoutMode =
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.Compact -> UserProfileLayoutMode.Compact
        WindowWidthSizeClass.Medium -> UserProfileLayoutMode.Medium
        else -> UserProfileLayoutMode.Expanded
    }

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private fun resolveUserProfileLayoutMode(windowSize: DpSize): UserProfileLayoutMode =
    resolveUserProfileLayoutMode(WindowSizeClass.calculateFromSize(windowSize).widthSizeClass)

private fun UserProfileViewData.initials(): String {
    val source = name.ifBlank { email }
    val first = source.trim().firstOrNull()?.toString()?.uppercase()
    return first ?: "#"
}

internal fun loginDeviceSupportingText(device: UserDeviceViewData): String =
    AppStrings.ui_login_time_arg0.format(arg0 = formatDeviceLoginAt(device.createdAt))

internal fun settingTargetSupportingText(target: SettingTargetViewData): String? =
    target.deviceKey?.let { AppStrings.ui_can_copied_this_device }

internal fun personalSettingsTargetMenuLabels(isTargetInUse: Boolean): List<String> =
    listOf(
        if (isTargetInUse) {
            PersonalSettingsTargetMenuLabelCancelUse
        } else {
            PersonalSettingsTargetMenuLabelUse
        },
        PersonalSettingsTargetMenuLabelCopy,
        PersonalSettingsTargetMenuLabelDelete,
    )

private fun String?.toSettingTargetDeviceType(): DeviceType =
    this?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value -> runCatching { DeviceType.valueOf(value) }.getOrNull() }
        ?: PlatformType

private val PersonalSettingsTargetMenuLabelUse: String
    get() = AppStrings.profile_use_settings
private val PersonalSettingsTargetMenuLabelCancelUse: String
    get() = AppStrings.profile_restore_device_settings
private val PersonalSettingsTargetMenuLabelCopy: String
    get() = AppStrings.profile_copy_settings_to_device
private val PersonalSettingsTargetMenuLabelDelete: String
    get() = AppStrings.profile_delete_settings

@Suppress("DEPRECATION")
private fun formatDeviceLoginAt(createdAt: Long): String {
    if (createdAt <= 0L) return AppStrings.ui_unknown

    val epochMilliseconds = if (createdAt >= 1_000_000_000_000L) {
        createdAt
    } else {
        createdAt * 1000
    }

    return runCatching {
        val localDateTime = Instant
            .fromEpochMilliseconds(epochMilliseconds)
            .toLocalDateTime(TimeZone.currentSystemDefault())

        buildString {
            append(localDateTime.year)
            append('-')
            append(localDateTime.monthNumber.pad2())
            append('-')
            append(localDateTime.dayOfMonth.pad2())
            append(' ')
            append(localDateTime.hour.pad2())
            append(':')
            append(localDateTime.minute.pad2())
            append(':')
            append(localDateTime.second.pad2())
        }
    }.getOrElse { AppStrings.ui_unknown }
}

private fun Int.pad2(): String = toString().padStart(2, '0')
