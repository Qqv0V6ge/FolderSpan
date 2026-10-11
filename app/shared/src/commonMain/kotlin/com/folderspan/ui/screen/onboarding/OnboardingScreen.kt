package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.utils.SettingsUtils

class OnboardingScreen(
    private val forceView: Boolean = false,
    private val onCompleted: (() -> Unit)? = null,
) : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        var pageIndex by rememberSaveable { mutableIntStateOf(0) }
        val pages = remember { onboardingPages }
        val lastIndex = pages.lastIndex
        val isLastPage = pageIndex == lastIndex

        fun closeGuide(markCompleted: Boolean) {
            if (markCompleted) {
                SettingsUtils.setOnboardingCompleted(true)
            }
            if (onCompleted != null) {
                onCompleted.invoke()
            } else if (forceView && navigator != null) {
                navigator.pop()
            }
        }

        AppScaffold(
            topBar = {
                if (forceView) {
                    TopAppBar(
                        title = { Text(AppStrings.ui_boot_page) },
                        navigationIcon = {
                            IconButton(onClick = { closeGuide(markCompleted = false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AnimatedContent(
                    targetState = pages[pageIndex],
                    transitionSpec = {
                        fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120))
                    },
                    label = "OnboardingPage",
                    modifier = Modifier.weight(1f)
                ) { page ->
                    OnboardingPageContent(
                        page = page,
                        pageNumber = pageIndex + 1,
                        pageCount = pages.size,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                OnboardingBottomBar(
                    currentIndex = pageIndex,
                    pageCount = pages.size,
                    skipLabel = if (forceView) AppStrings.ui_close else AppStrings.ui_skip,
                    nextLabel = if (isLastPage) AppStrings.ui_get_started else AppStrings.ui_next_step,
                    onSkip = { closeGuide(markCompleted = !forceView) },
                    onNext = {
                        if (isLastPage) {
                            closeGuide(markCompleted = true)
                        } else {
                            pageIndex += 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    pageNumber: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    var selectedStepIndex by rememberSaveable(page.key) {
        mutableIntStateOf(initialOnboardingStepIndex())
    }
    val selectedActionIndex = resolveOnboardingStepSelection(
        requestedIndex = selectedStepIndex,
        stepCount = page.actions.size
    )
    val selectStep: (Int) -> Unit = { requestedIndex ->
        selectedStepIndex = resolveOnboardingStepSelection(
            requestedIndex = requestedIndex,
            stepCount = page.actions.size
        )
    }

    BoxWithConstraints(modifier = modifier) {
        val contentLayout = resolveOnboardingContentLayout(width = maxWidth, height = maxHeight)
        val horizontalPadding = if (contentLayout == OnboardingContentLayout.Row) 32.dp else 20.dp

        if (contentLayout == OnboardingContentLayout.Row) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OnboardingTargetPreview(
                    page = page,
                    selectedStepIndex = selectedActionIndex,
                    scrollable = true,
                    contentLayout = contentLayout,
                    showHeader = shouldShowOnboardingActionHeader(contentLayout),
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxHeight()
                        .padding(vertical = 24.dp)
                        .heightIn(min = onboardingTargetPreviewMinHeight(contentLayout))
                )
                OnboardingCopy(
                    page = page,
                    pageNumber = pageNumber,
                    pageCount = pageCount,
                    selectedStepIndex = selectedActionIndex,
                    onStepSelected = selectStep,
                    modifier = Modifier
                        .weight(0.92f)
                        .widthIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                OnboardingCopy(
                    page = page,
                    pageNumber = pageNumber,
                    pageCount = pageCount,
                    selectedStepIndex = selectedActionIndex,
                    onStepSelected = selectStep,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
                OnboardingTargetPreview(
                    page = page,
                    selectedStepIndex = selectedActionIndex,
                    scrollable = false,
                    contentLayout = contentLayout,
                    showHeader = shouldShowOnboardingActionHeader(contentLayout),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = onboardingTargetPreviewMinHeight(contentLayout))
                )
            }
        }
    }
}

@Composable
private fun OnboardingCopy(
    page: OnboardingPage,
    pageNumber: Int,
    pageCount: Int,
    selectedStepIndex: Int,
    onStepSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(page.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = "$pageNumber/$pageCount · ${page.label}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (shouldShowOnboardingStepSection()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = onboardingStepSectionTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                page.actions.forEachIndexed { index, action ->
                    OnboardingActionTextRow(
                        index = index + 1,
                        action = action,
                        selected = index == selectedStepIndex,
                        enabled = isOnboardingStepClickable(
                            stepIndex = index,
                            selectedStepIndex = selectedStepIndex,
                            stepCount = page.actions.size
                        ),
                        onClick = { onStepSelected(index) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Immutable
internal data class OnboardingActionColors(
    val container: Color,
    val title: Color,
    val body: Color,
)

internal fun resolveOnboardingActionColors(
    colorScheme: ColorScheme,
    selected: Boolean,
    enabled: Boolean,
): OnboardingActionColors =
    OnboardingActionColors(
        container = if (selected) {
            colorScheme.secondaryContainer
        } else {
            colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        title = when {
            selected -> colorScheme.onSecondaryContainer
            enabled -> colorScheme.onSurface
            else -> colorScheme.onSurface.copy(alpha = 0.38f)
        },
        body = when {
            selected -> colorScheme.onSecondaryContainer
            enabled -> colorScheme.onSurfaceVariant
            else -> colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
    )

@Composable
private fun OnboardingActionTextRow(
    index: Int,
    action: OnboardingAction,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = resolveOnboardingActionColors(
        colorScheme = MaterialTheme.colorScheme,
        selected = selected,
        enabled = enabled,
    )

    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = colors.container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = action.target,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.title,
                )
                Text(
                    text = action.action,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.body,
                )
            }
        }
    }
}

@Composable
private fun OnboardingTargetPreview(
    page: OnboardingPage,
    selectedStepIndex: Int,
    scrollable: Boolean,
    contentLayout: OnboardingContentLayout,
    showHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    Column(
        modifier = modifier.then(scrollModifier),
        verticalArrangement = if (shouldCenterOnboardingTargetPreviewContent(contentLayout)) {
            Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        } else {
            Arrangement.spacedBy(18.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showHeader && shouldShowOnboardingTargetPreviewTitle()) {
            OnboardingActionHeader(
                page = page,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (shouldShowOnboardingTargetPreviewCardBackground()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                OnboardingTargetPreviewContent(
                    page = page,
                    selectedStepIndex = selectedStepIndex,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
        } else {
            OnboardingTargetPreviewContent(
                page = page,
                selectedStepIndex = selectedStepIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun OnboardingTargetPreviewContent(
    page: OnboardingPage,
    selectedStepIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (shouldShowOnboardingTargetPreviewTitle()) {
            OnboardingTargetTitle(page = page)
        }
        if (shouldShowOnboardingTargetPreviewStepInfo()) {
            selectedOnboardingAction(page, selectedStepIndex)?.let { action ->
                OnboardingPreviewStepInfo(
                    stepNumber = selectedStepIndex + 1,
                    action = action,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (shouldAnimateOnboardingTargetPreviewContent()) {
            AnimatedContent(
                targetState = selectedStepIndex,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(onboardingTargetPreviewEnterAnimationMillis())
                    ) togetherWith fadeOut(
                        animationSpec = tween(onboardingTargetPreviewExitAnimationMillis())
                    )
                },
                label = "OnboardingTargetPreviewStep",
                modifier = Modifier.fillMaxWidth()
            ) { targetStepIndex ->
                OnboardingTargetWidget(
                    widgetType = page.previewType,
                    selectedStepIndex = targetStepIndex,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            OnboardingTargetWidget(
                widgetType = page.previewType,
                selectedStepIndex = selectedStepIndex,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OnboardingActionHeader(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(page.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OnboardingTargetTitle(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(page.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.targetScreenTitle(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = page.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OnboardingPreviewStepInfo(
    stepNumber: Int,
    action: OnboardingAction,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = AppStrings.ui_step_arg0_arg1.format(arg0 = (stepNumber).toString(), arg1 = action.target),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = action.action,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun OnboardingPage.targetScreenTitle(): String =
    when (widgetType) {
        OnboardingWidgetType.LinkShare -> AppStrings.ui_share_page
        OnboardingWidgetType.DeviceShare,
        OnboardingWidgetType.RemoteFiles -> AppStrings.ui_device_page

        OnboardingWidgetType.CloudDrive -> AppStrings.ui_add_network_location
    }

internal fun shouldShowOnboardingActionHeader(contentLayout: OnboardingContentLayout): Boolean =
    contentLayout == OnboardingContentLayout.Column

internal fun shouldCenterOnboardingTargetPreviewContent(): Boolean =
    shouldCenterOnboardingTargetPreviewContent(OnboardingContentLayout.Row)

internal fun shouldCenterOnboardingTargetPreviewContent(
    contentLayout: OnboardingContentLayout,
): Boolean =
    contentLayout == OnboardingContentLayout.Row

internal fun onboardingTargetPreviewMinHeight(
    contentLayout: OnboardingContentLayout,
): Dp =
    when (contentLayout) {
        OnboardingContentLayout.Row -> 420.dp
        OnboardingContentLayout.Column -> 0.dp
    }

internal fun shouldAnimateOnboardingTargetPreviewContent(): Boolean = true

internal fun onboardingTargetPreviewEnterAnimationMillis(): Int = 180

internal fun onboardingTargetPreviewExitAnimationMillis(): Int = 120

internal fun shouldShowOnboardingTargetPreviewCardBackground(): Boolean = false

internal fun shouldShowOnboardingTargetPreviewTitle(): Boolean = false

internal fun shouldShowOnboardingTargetPreviewStepInfo(): Boolean = false

internal fun shouldShowOnboardingOpenAction(): Boolean = false

internal fun shouldShowOnboardingCompletionSummary(): Boolean = false

internal fun shouldShowOnboardingStepSection(): Boolean = true

internal fun onboardingStepSectionTitle(): String = AppStrings.ui_operation_steps

internal fun initialOnboardingStepIndex(): Int = 0

internal fun shouldAllowOnboardingStepJump(): Boolean = true

internal fun resolveOnboardingStepSelection(
    requestedIndex: Int,
    stepCount: Int,
): Int =
    if (stepCount <= 0) {
        initialOnboardingStepIndex()
    } else {
        requestedIndex.coerceIn(0, stepCount - 1)
    }

internal fun isOnboardingStepClickable(
    stepIndex: Int,
    selectedStepIndex: Int,
    stepCount: Int,
): Boolean =
    shouldAllowOnboardingStepJump() &&
            stepIndex in 0 until stepCount &&
            selectedStepIndex in 0 until stepCount

internal fun isOnboardingPreviewNodeUnlocked(
    nodeIndex: Int,
    selectedStepIndex: Int,
): Boolean =
    nodeIndex == selectedStepIndex

internal fun selectedOnboardingAction(
    page: OnboardingPage,
    selectedStepIndex: Int,
): OnboardingAction? =
    page.actions.getOrNull(
        resolveOnboardingStepSelection(
            requestedIndex = selectedStepIndex,
            stepCount = page.actions.size
        )
    )

@Composable
private fun OnboardingBottomBar(
    currentIndex: Int,
    pageCount: Int,
    skipLabel: String,
    nextLabel: String,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 20.dp, vertical = 14.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = onSkip) {
                Text(skipLabel)
            }
            Spacer(Modifier.weight(1f))
            PageIndicator(
                currentIndex = currentIndex,
                pageCount = pageCount
            )
            Button(onClick = onNext) {
                Text(nextLabel)
            }
        }
    }
}

@Composable
private fun PageIndicator(
    currentIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(pageCount) { index ->
            val active = index == currentIndex
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                label = "OnboardingIndicatorColor"
            )
            Box(
                modifier = Modifier
                    .width(if (active) 22.dp else 8.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
