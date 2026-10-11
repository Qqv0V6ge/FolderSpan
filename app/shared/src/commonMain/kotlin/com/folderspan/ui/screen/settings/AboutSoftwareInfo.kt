package com.folderspan.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.composeapp.generated.resources.Res
import com.folderspan.composeapp.generated.resources.app_icon
import com.folderspan.ui.screen.onboarding.WelcomeAgreementDocumentType
import org.jetbrains.compose.resources.painterResource
import strings.AppStrings

internal val AboutSoftwareContentMaxWidth = 840.dp
internal val AboutSoftwareSpacingXs = 4.dp
internal val AboutSoftwareSpacingSm = 8.dp
internal val AboutSoftwareSpacingMd = 16.dp
internal val AboutSoftwareSpacingLg = 24.dp
internal val AboutSoftwareIconWellSize = 96.dp
internal val AboutSoftwareIconSize = 72.dp
internal val AboutSoftwareTouchTarget = 48.dp
internal const val ABOUT_SOFTWARE_WEBSITE_URL = "https://www.folderspan.com"
internal const val ABOUT_SOFTWARE_GITHUB_URL = "https://github.com/Qqv0V6ge/FolderSpan"
internal const val ABOUT_SOFTWARE_OTHER_PLATFORMS_URL = "https://www.folderspan.com/download"


internal fun aboutSoftwareHorizontalPadding(maxWidth: Dp): Dp =
    if (maxWidth < 600.dp) AboutSoftwareSpacingMd else AboutSoftwareSpacingLg

internal fun aboutSoftwareVerticalPadding(maxHeight: Dp): Dp =
    if (maxHeight < 480.dp) AboutSoftwareSpacingSm else AboutSoftwareSpacingMd


@Composable
internal fun AboutSoftwareAutoCaptureLogsItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(AppStrings.settings_about_software_auto_capture_logs) },
        supportingContent = { Text(AppStrings.settings_about_software_auto_capture_logs_subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("about-software-auto-capture-switch"),
            )
        },
        colors = aboutSoftwareListItemColors(),
        modifier = modifier
            .heightIn(min = AboutSoftwareTouchTarget)
            .testTag("about-software-auto-capture"),
    )
}

@Composable
internal fun AboutSoftwareExternalLinkItem(
    title: String,
    url: String,
    testTag: String,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(url) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        },
        colors = aboutSoftwareListItemColors(),
        modifier = modifier
            .heightIn(min = AboutSoftwareTouchTarget)
            .clickable { onOpenLink(url) }
            .testTag(testTag)
            .semantics { role = Role.Button },
    )
}


@Composable
internal fun AboutSoftwareNavigationItem(
    title: String,
    supportingText: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        },
        colors = aboutSoftwareListItemColors(),
        modifier = modifier
            .heightIn(min = AboutSoftwareTouchTarget)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { role = Role.Button },
    )
}

@Composable
internal fun AboutSoftwareIdentitySection(
    version: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(AboutSoftwareSpacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingSm),
    ) {
        Box(
            modifier = Modifier
                .size(AboutSoftwareIconWellSize)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(AboutSoftwareIconSize)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        Text(
            text = AppStrings.app_name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = AppStrings.settings_about_software_version,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = version,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("about-software-version"),
        )
    }
}


@Composable
internal fun aboutSoftwareListItemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
)


@Composable
internal fun AboutSoftwareLinks(
    onOpenLink: (String) -> Unit,
    autoCaptureLogs: Boolean,
    onAutoCaptureLogsChange: (Boolean) -> Unit,
    onOpenDocument: (WelcomeAgreementDocumentType) -> Unit,
) {
        AboutSoftwareNavigationItem(
            title = AppStrings.ui_user_agreement,
            supportingText = AppStrings.settings_about_software_user_agreement_subtitle,
            testTag = "about-software-user-agreement",
            onClick = { onOpenDocument(WelcomeAgreementDocumentType.UserAgreement) },
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareNavigationItem(
            title = AppStrings.ui_privacy_policy,
            supportingText = AppStrings.settings_about_software_privacy_policy_subtitle,
            testTag = "about-software-privacy-policy",
            onClick = { onOpenDocument(WelcomeAgreementDocumentType.PrivacyPolicy) },
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareExternalLinkItem(
            title = AppStrings.settings_about_software_website,
            url = ABOUT_SOFTWARE_WEBSITE_URL,
            testTag = "about-software-website",
            onOpenLink = onOpenLink,
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareExternalLinkItem(
            title = AppStrings.settings_about_software_github,
            url = ABOUT_SOFTWARE_GITHUB_URL,
            testTag = "about-software-github",
            onOpenLink = onOpenLink,
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareExternalLinkItem(
            title = AppStrings.settings_about_software_download_other_platforms,
            url = ABOUT_SOFTWARE_OTHER_PLATFORMS_URL,
            testTag = "about-software-download-other-platforms",
            onOpenLink = onOpenLink,
            modifier = Modifier.fillMaxWidth(),
        )
        AboutSoftwareAutoCaptureLogsItem(
            checked = autoCaptureLogs,
            onCheckedChange = onAutoCaptureLogsChange,
            modifier = Modifier.fillMaxWidth(),
        )
}
