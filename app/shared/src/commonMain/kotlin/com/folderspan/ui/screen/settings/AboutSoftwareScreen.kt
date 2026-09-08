package com.folderspan.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.openUrl
import com.folderspan.pro.di.AppUpdateRuntime
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.domain.model.AppUpdateSnapshot
import com.folderspan.pro.domain.model.AppUpdateStatus
import com.folderspan.ui.components.agreement.AgreementDocumentDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.onboarding.WelcomeAgreementDocumentType
import com.folderspan.ui.screen.onboarding.welcomeAgreementDocument
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.currentAppVersion
import com.folderspan.utils.httpOrHttpsUrlOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings

internal fun shouldShowAppUpdateCheck(platformType: DeviceType = PlatformType): Boolean =
    platformType != DeviceType.JS

class AboutSoftwareScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val appUpdateRuntime = koinInject<AppUpdateRuntime>()
        val settingsState = koinInject<SettingsState>()
        val snapshot by appUpdateRuntime.state.collectAsState()
        val channel by appUpdateRuntime.selectedChannel.collectAsState()
        val autoCaptureLogs by settingsState.autoCaptureLogs.collectAsState()
        val scope = rememberCoroutineScope()
        var pageCheckStarted by remember { mutableStateOf(false) }
        val displayedSnapshot = if (pageCheckStarted) snapshot else AppUpdateSnapshot()

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.settings_about_software_title) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                        }
                    }
                )
            }
        ) { padding ->
            AboutSoftwareContent(
                version = currentAppVersion(),
                showCheck = shouldShowAppUpdateCheck(),
                snapshot = displayedSnapshot,
                channel = channel,
                onCheck = {
                    pageCheckStarted = true
                    scope.launch { appUpdateRuntime.check(notify = false) }
                },
                onChannelChange = { next ->
                    pageCheckStarted = false
                    appUpdateRuntime.setChannel(next)
                },
                onOpenLink = { url -> openUrl(url) },
                onOpenHistory = { navigator.push(AppUpdateHistoryScreen()) },
                autoCaptureLogs = autoCaptureLogs,
                onAutoCaptureLogsChange = settingsState::setAutoCaptureLogs,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
internal fun AboutSoftwareContent(
    version: String,
    showCheck: Boolean,
    snapshot: AppUpdateSnapshot,
    onCheck: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    channel: AppUpdateChannel = AppUpdateChannel.Release,
    onChannelChange: (AppUpdateChannel) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    autoCaptureLogs: Boolean = false,
    onAutoCaptureLogsChange: (Boolean) -> Unit = {},
) {
    val update = snapshot.update
    val openLink = update?.let { httpOrHttpsUrlOrNull(it.link) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var selectedDocumentType by remember { mutableStateOf<WelcomeAgreementDocumentType?>(null) }
    var wasChecking by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.status, snapshot.update?.id, snapshot.update?.version, selectedDocumentType) {
        if (
            wasChecking &&
            snapshot.status == AppUpdateStatus.Newer &&
            snapshot.update != null &&
            selectedDocumentType == null
        ) {
            showUpdateDialog = true
        }
        wasChecking = snapshot.status == AppUpdateStatus.Checking
    }
    LaunchedEffect(channel) {
        showUpdateDialog = false
    }
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = AboutSoftwareContentMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(bottom = aboutSoftwareVerticalPadding(maxHeight)),
            verticalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingMd),
        ) {
            AboutSoftwareIdentitySection(
                version = version,
                modifier = Modifier.fillMaxWidth(),
            )
            AboutSoftwareActionsSection(
                showCheck = showCheck,
                snapshot = snapshot,
                channel = channel,
                onCheck = onCheck,
                onChannelChange = onChannelChange,
                onViewNewer = { showUpdateDialog = true },
                onOpenHistory = onOpenHistory,
                onOpenLink = onOpenLink,
                autoCaptureLogs = autoCaptureLogs,
                onAutoCaptureLogsChange = onAutoCaptureLogsChange,
                onOpenDocument = { type ->
                    showUpdateDialog = false
                    selectedDocumentType = type
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (showUpdateDialog && update != null) {
            AboutSoftwareUpdateDialog(
                update = update,
                openLink = openLink,
                notesMaxHeight = if (maxHeight < 480.dp) 160.dp else 320.dp,
                onOpenLink = onOpenLink,
                onDismiss = { showUpdateDialog = false },
            )
        }
        selectedDocumentType?.let { type ->
            val document = welcomeAgreementDocument(type)
            AgreementDocumentDialog(
                title = document.title,
                paragraphs = document.paragraphs,
                onDismiss = { selectedDocumentType = null },
            )
        }
    }
}

@Composable
private fun AboutSoftwareActionsSection(
    showCheck: Boolean,
    snapshot: AppUpdateSnapshot,
    channel: AppUpdateChannel,
    onCheck: () -> Unit,
    onChannelChange: (AppUpdateChannel) -> Unit,
    onViewNewer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLink: (String) -> Unit,
    autoCaptureLogs: Boolean,
    onAutoCaptureLogsChange: (Boolean) -> Unit,
    onOpenDocument: (WelcomeAgreementDocumentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (showCheck) {
            AboutSoftwareChannelSelector(
                channel = channel,
                enabled = snapshot.status != AppUpdateStatus.Checking,
                onChannelChange = onChannelChange,
                modifier = Modifier.fillMaxWidth(),
            )
            AboutSoftwareCheckItem(
                snapshot = snapshot,
                onCheck = onCheck,
                modifier = Modifier.fillMaxWidth(),
            )
            if (snapshot.status == AppUpdateStatus.Newer) {
                TextButton(
                    onClick = onViewNewer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AboutSoftwareTouchTarget)
                        .testTag("about-software-newer"),
                ) {
                    Text(AppStrings.settings_about_software_view_notes)
                }
            }
            if (snapshot.status == AppUpdateStatus.Error) {
                AboutSoftwareCheckError(
                    message = snapshot.errorMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AboutSoftwareSpacingMd,
                            end = AboutSoftwareSpacingMd,
                            bottom = AboutSoftwareSpacingMd,
                        ),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        AboutSoftwareHistoryItem(
            onOpenHistory = onOpenHistory,
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareLinks(onOpenLink, autoCaptureLogs, onAutoCaptureLogsChange, onOpenDocument)
    }
}

@Composable
internal fun AboutSoftwareHistoryItem(
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AboutSoftwareNavigationItem(
        title = AppStrings.settings_about_software_history,
        supportingText = AppStrings.settings_about_software_history_subtitle,
        testTag = "about-software-history",
        onClick = onOpenHistory,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSoftwareChannelSelector(
    channel: AppUpdateChannel,
    enabled: Boolean,
    onChannelChange: (AppUpdateChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val channels = AppUpdateChannel.entries
    Column(
        modifier = modifier
            .testTag("about-software-channel")
            .padding(AboutSoftwareSpacingMd),
        verticalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingSm),
    ) {
        Text(
            text = AppStrings.settings_about_software_channel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            channels.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == channel,
                    onClick = { onChannelChange(item) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, channels.size),
                    modifier = Modifier.testTag("about-software-channel-${item.token}"),
                ) {
                    Text(aboutSoftwareChannelLabel(item))
                }
            }
        }
    }
}

@Composable
private fun AboutSoftwareCheckItem(
    snapshot: AppUpdateSnapshot,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checking = snapshot.status == AppUpdateStatus.Checking
    val statusText = aboutSoftwareStatusText(snapshot)
    ListItem(
        headlineContent = { Text(AppStrings.settings_about_software_check_updates) },
        supportingContent = if (statusText.isNotEmpty()) {
            {
                Text(
                    text = statusText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else {
            null
        },
        leadingContent = {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = aboutSoftwareListItemColors(),
        modifier = modifier
            .heightIn(min = AboutSoftwareTouchTarget)
            .clickable(enabled = !checking, onClick = onCheck)
            .testTag("about-software-check")
            .semantics { role = Role.Button },
    )
}

@Composable
private fun AboutSoftwareCheckError(
    message: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message ?: AppStrings.settings_about_software_check_failed,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(AboutSoftwareSpacingMd)
            .testTag("about-software-error")
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@Composable
internal fun AboutSoftwareUpdateDialog(
    update: AppUpdate,
    openLink: String?,
    notesMaxHeight: Dp,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = AppStrings.settings_about_software_newer_available,
) {
    val notes = aboutSoftwareChangelogLines(update.content)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            AboutSoftwareUpdateNotes(
                update = update,
                notes = notes,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = notesMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            if (openLink != null) {
                TextButton(
                    onClick = {
                        onOpenLink(openLink)
                        onDismiss()
                    },
                    modifier = Modifier
                        .heightIn(min = AboutSoftwareTouchTarget)
                        .testTag("about-software-open-link"),
                ) {
                    Text(AppStrings.settings_about_software_open_link)
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = AboutSoftwareTouchTarget),
                ) {
                    Text(AppStrings.ui_got_it)
                }
            }
        },
        dismissButton = if (openLink != null) {
            {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = AboutSoftwareTouchTarget),
                ) {
                    Text(AppStrings.ui_got_it)
                }
            }
        } else {
            null
        },
    )
}

@Composable
internal fun AboutSoftwareUpdateNotes(
    update: AppUpdate,
    notes: List<String>,
    modifier: Modifier = Modifier,
) {
    val title = update.title.trim()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingSm),
    ) {
        if (AppUpdateChannel.fromToken(update.channel) == AppUpdateChannel.Beta) {
            Text(
                text = AppStrings.settings_about_software_channel_beta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (title.isNotEmpty() && title != update.version) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (update.version.isNotEmpty()) {
            Text(
                text = update.version,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (notes.size > 1) {
            notes.forEach { line ->
                AboutSoftwareNoteRow(line)
            }
        } else if (notes.size == 1) {
            Text(
                text = notes.single(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AboutSoftwareNoteRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingSm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = AboutSoftwareSpacingXs),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun aboutSoftwareStatusText(snapshot: AppUpdateSnapshot): String = when (snapshot.status) {
    AppUpdateStatus.Idle -> AppStrings.settings_about_software_check_description
    AppUpdateStatus.Checking -> AppStrings.settings_about_software_checking
    AppUpdateStatus.UpToDate -> AppStrings.settings_about_software_up_to_date
    AppUpdateStatus.Newer -> AppStrings.settings_about_software_newer_available
    AppUpdateStatus.Error -> ""
}

internal fun aboutSoftwareChangelogLines(content: String): List<String> =
    content.lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .map { line ->
            line.removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("• ")
        }
        .toList()

internal fun aboutSoftwareChannelLabel(channel: AppUpdateChannel): String = when (channel) {
    AppUpdateChannel.Release -> AppStrings.settings_about_software_channel_release
    AppUpdateChannel.Beta -> AppStrings.settings_about_software_channel_beta
}

private val previewNewerUpdate = AppUpdate(
    id = 1L,
    title = AppStrings.ui_linux_desktop_2_5_0_release,
    content = AppStrings.settings_about_software_changelog_preview,
    version = "2.5.0",
    platform = "linux",
    link = "https://example.test/app",
    publishedAtEpochMillis = 1L,
)

@Preview(name = "Idle compact", widthDp = 360, heightDp = 800)
@Composable
private fun AboutSoftwareIdleCompactPreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = true,
            snapshot = AppUpdateSnapshot(),
            onCheck = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Web compact", widthDp = 360, heightDp = 800)
@Composable
private fun AboutSoftwareWebCompactPreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = false,
            snapshot = AppUpdateSnapshot(),
            onCheck = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Newer compact", widthDp = 360, heightDp = 800)
@Composable
private fun AboutSoftwareNewerCompactPreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = true,
            snapshot = AppUpdateSnapshot(
                status = AppUpdateStatus.Newer,
                update = previewNewerUpdate,
            ),
            onCheck = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Newer expanded", widthDp = 1024, heightDp = 768)
@Composable
private fun AboutSoftwareNewerExpandedPreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = true,
            snapshot = AppUpdateSnapshot(
                status = AppUpdateStatus.Newer,
                update = previewNewerUpdate,
            ),
            onCheck = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Up to date compact height", widthDp = 640, heightDp = 360)
@Composable
private fun AboutSoftwareUpToDatePreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = true,
            snapshot = AppUpdateSnapshot(status = AppUpdateStatus.UpToDate),
            onCheck = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Error extra-large", widthDp = 1600, heightDp = 900)
@Composable
private fun AboutSoftwareErrorPreview() {
    MaterialTheme {
        AboutSoftwareContent(
            version = "1.0.0",
            showCheck = true,
            snapshot = AppUpdateSnapshot(
                status = AppUpdateStatus.Error,
                errorMessage = AppStrings.settings_about_software_check_failed,
            ),
            onCheck = {},
            onOpenLink = {},
        )
    }
}
