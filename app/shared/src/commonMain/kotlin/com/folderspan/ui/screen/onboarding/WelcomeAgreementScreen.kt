package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.composeapp.generated.resources.Res
import com.folderspan.ui.components.agreement.AgreementDocumentDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.utils.SettingsUtils
import com.seiko.imageloader.model.ImageRequest
import com.seiko.imageloader.rememberImagePainter

class WelcomeAgreementScreen(
    private val onAccepted: () -> Unit,
) : AppScreenRoute {
    @Composable
    override fun Content() {
        var agreementAccepted by rememberSaveable { mutableStateOf(false) }
        var selectedDocumentType by remember { mutableStateOf<WelcomeAgreementDocumentType?>(null) }

        AppScaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                BoxWithConstraints(
                    modifier = Modifier.weight(1f)
                ) {
                    val contentLayout = resolveWelcomeAgreementContentLayout(
                        width = maxWidth,
                        height = maxHeight
                    )
                    WelcomeAgreementContent(
                        layout = contentLayout,
                        onDocumentRequested = { item -> selectedDocumentType = item },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                WelcomeAgreementBottomBar(
                    agreementAccepted = agreementAccepted,
                    onAgreementAcceptedChange = { item -> agreementAccepted = item },
                    onDocumentRequested = { item -> selectedDocumentType = item },
                    onContinue = {
                        if (canContinueWelcomeAgreement(agreementAccepted)) {
                            SettingsUtils.setWelcomeAgreementAccepted(true)
                            onAccepted()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        selectedDocumentType?.let { type ->
            val document = welcomeAgreementDocument(type)
            AgreementDocumentDialog(
                title = document.title,
                paragraphs = document.paragraphs,
                onDismiss = { selectedDocumentType = null }
            )
        }
    }
}

@Composable
private fun WelcomeAgreementContent(
    layout: WelcomeAgreementContentLayout,
    onDocumentRequested: (WelcomeAgreementDocumentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val maxContentWidth = welcomeAgreementContentMaxWidth(layout)

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background),
    ) {
        val viewportHeight = maxHeight
        val contentAlignment = when (welcomeAgreementContentPlacement(layout)) {
            WelcomeAgreementContentPlacement.Center -> Alignment.Center
            WelcomeAgreementContentPlacement.Top -> Alignment.TopCenter
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldScrollWelcomeAgreementContent()) {
                        Modifier.verticalScroll(scrollState)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = contentAlignment
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                contentAlignment = contentAlignment
            ) {
                if (layout == WelcomeAgreementContentLayout.Row) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = maxContentWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WelcomeAgreementHero(
                            modifier = Modifier.weight(0.9f)
                        )
                        WelcomeAgreementDetails(
                            onDocumentRequested = onDocumentRequested,
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = maxContentWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        WelcomeAgreementHero()
                        WelcomeAgreementDetails(
                            onDocumentRequested = onDocumentRequested,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeAgreementHero(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                FolderSpanLogo(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Text(
                    text = welcomeAgreementTitle(),
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = welcomeAgreementSubtitle(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                )
            }
        }
        WelcomeAgreementPolicySummary()
    }
}

@Composable
private fun FolderSpanLogo(
    modifier: Modifier = Modifier,
) {
    val svgBytes by produceState<ByteArray?>(initialValue = null) {
        value = Res.readBytes("files/icon.svg")
    }

    Box(modifier = modifier) {
        svgBytes?.let { bytes ->
            val request = remember(bytes) {
                ImageRequest {
                    data(bytes)
                }
            }
            Image(
                painter = rememberImagePainter(request),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun WelcomeAgreementPolicySummary(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = AppStrings.ui_please_confirm_before_starting,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        listOf(
            AppStrings.ui_file_access_only_used_browsing_management_transfer_operations_initiated,
            AppStrings.ui_lan_discovery_device_connection_only_used_when_required_software,
            AppStrings.ui_default_application_will_not_actively_upload_local_files
        ).forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WelcomeAgreementDetails(
    onDocumentRequested: (WelcomeAgreementDocumentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        welcomeAgreementFeatures.forEach { feature ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = feature.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = feature.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = { onDocumentRequested(WelcomeAgreementDocumentType.PrivacyPolicy) },
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(AppStrings.ui_see_how_these_features_covered_privacy_policy)
        }
    }
}

@Composable
private fun WelcomeAgreementBottomBar(
    agreementAccepted: Boolean,
    onAgreementAcceptedChange: (Boolean) -> Unit,
    onDocumentRequested: (WelcomeAgreementDocumentType) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val useRow = maxWidth > 640.dp
            if (useRow) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 1120.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgreementConsent(
                        agreementAccepted = agreementAccepted,
                        onAgreementAcceptedChange = onAgreementAcceptedChange,
                        onDocumentRequested = onDocumentRequested,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onContinue,
                        enabled = canContinueWelcomeAgreement(agreementAccepted)
                    ) {
                        Text(AppStrings.ui_agree_continue)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AgreementConsent(
                        agreementAccepted = agreementAccepted,
                        onAgreementAcceptedChange = onAgreementAcceptedChange,
                        onDocumentRequested = onDocumentRequested,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onContinue,
                        enabled = canContinueWelcomeAgreement(agreementAccepted),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(AppStrings.ui_agree_continue)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgreementConsent(
    agreementAccepted: Boolean,
    onAgreementAcceptedChange: (Boolean) -> Unit,
    onDocumentRequested: (WelcomeAgreementDocumentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onAgreementAcceptedChange(!agreementAccepted) }
            .padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = agreementAccepted,
            onCheckedChange = onAgreementAcceptedChange
        )
        Column {
            Text(
                text = AppStrings.ui_i_have_read_agree,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onDocumentRequested(WelcomeAgreementDocumentType.UserAgreement) },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text(AppStrings.ui_user_agreement)
                }
                Text(
                    text = AppStrings.ui_conjunction_and,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { onDocumentRequested(WelcomeAgreementDocumentType.PrivacyPolicy) },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text(AppStrings.ui_privacy_policy)
                }
            }
        }
    }
}
