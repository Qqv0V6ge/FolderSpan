package com.folderspan.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.openUrl
import com.folderspan.ui.components.agreement.AgreementDocumentDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.onboarding.WelcomeAgreementDocumentType
import com.folderspan.ui.screen.onboarding.welcomeAgreementDocument
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.currentAppVersion
import org.koin.compose.koinInject
import strings.AppStrings

class AboutSoftwareScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val settingsState = koinInject<SettingsState>()
        val autoCaptureLogs by settingsState.autoCaptureLogs.collectAsState()
        AppScaffold(topBar = {
            TopAppBar(
                title = { Text(AppStrings.settings_about_software_title) },
                navigationIcon = {
                    IconButton({ navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, AppStrings.ui_return)
                    }
                },
            )
        }) { padding ->
            LocalAboutSoftwareContent(
                version = currentAppVersion(),
                autoCaptureLogs = autoCaptureLogs,
                onAutoCaptureLogsChange = settingsState::setAutoCaptureLogs,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
internal fun LocalAboutSoftwareContent(
    version: String,
    autoCaptureLogs: Boolean,
    onAutoCaptureLogsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDocumentType by remember { mutableStateOf<WelcomeAgreementDocumentType?>(null) }
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .widthIn(max = AboutSoftwareContentMaxWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = aboutSoftwareHorizontalPadding(maxWidth),
                    end = aboutSoftwareHorizontalPadding(maxWidth),
                    bottom = aboutSoftwareVerticalPadding(maxHeight),
                ),
            verticalArrangement = Arrangement.spacedBy(AboutSoftwareSpacingMd),
        ) {
            AboutSoftwareIdentitySection(version, Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth()) {
                AboutSoftwareLinks(
                    onOpenLink = { openUrl(it) },
                    autoCaptureLogs = autoCaptureLogs,
                    onAutoCaptureLogsChange = onAutoCaptureLogsChange,
                    onOpenDocument = { selectedDocumentType = it },
                )
            }
        }
    }
    selectedDocumentType?.let { type ->
        val document = welcomeAgreementDocument(type)
        AgreementDocumentDialog(document.title, document.paragraphs, onDismiss = { selectedDocumentType = null })
    }
}

@Preview(name = "Local compact", widthDp = 360, heightDp = 800)
@Preview(name = "Local medium", widthDp = 700, heightDp = 800)
@Preview(name = "Local expanded", widthDp = 1000, heightDp = 800)
@Preview(name = "Local large", widthDp = 1400, heightDp = 900)
@Preview(name = "Local extra large", widthDp = 1800, heightDp = 900)
@Preview(name = "Local short", widthDp = 640, heightDp = 360)
@Composable
private fun LocalAboutSoftwarePreview() {
    MaterialTheme { LocalAboutSoftwareContent("1.0.0", false, {}) }
}
