package com.folderspan.ui.screen.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.network.*
import com.folderspan.service.network.*
import com.folderspan.ui.components.buttons.TestConnectionButton
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.screen.network.form.*
import kotlinx.coroutines.runBlocking
import strings.AppStrings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Product form components rendered with invented demo values and in-memory capabilities; no network or account IO. */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class NetworkContentDocumentationTest {
    @Test
    fun captureCompletedExampleFormsWithoutLoggingIn() {
        for (protocol in listOf("FTP", "SFTP", "SMB", "WebDav", "S3", "NFS", "GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) {
            runSkikoComposeUiTest(size = Size(900f, 1400f)) {
                val operations = PasswordContentOperations()
                val network = Network("Demo storage", "/", protocol, "nas.example.test", "demo-user", "demo-login-password")
                val content = StorageContentFormState(protocol, network.connectionConfig(), mutableStateOf(false), operations)
                runBlocking { content.inspect() }
                val label = if (protocol == "GoogleDrive") "Google Drive" else protocol
                setContent {
                    MaterialTheme {
                        AppScaffold(
                            topBar = { TopAppBar(title = { Text(AppStrings.ui_add_network) },
                                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Default.ArrowBack, null) } }) },
                            floatingActionButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
                                    FloatingActionButton(onClick = {}) { Icon(Icons.Default.Save, AppStrings.ui_save) }
                                    TestConnectionButton(false, {})
                                }
                            },
                        ) { padding ->
                            GridList(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                                verticalSpacing = 16.dp, horizontalSpacing = 16.dp,
                                floatingActionButtonPadding = GridListFabPadding) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    EditableExposedDropdownMenu(StringListUiState(listOf(label)), label, {},
                                        readOnly = true, label = { Text(AppStrings.ui_agreement) },
                                        optionContent = { Text(it) }, modifier = Modifier.fillMaxWidth())
                                }
                                exampleForm(protocol)
                                item(span = { GridItemSpan(maxLineSpan) }) { StorageContentSection(content) }
                            }
                        }
                    }
                }
                onNodeWithTag("storage-content-section").assertIsDisplayed()
                onNodeWithTag("storage-content-toggle").assertIsOff().assertIsEnabled()
                onNodeWithTag("storage-content-password-input").assertDoesNotExist()
                val name = if (protocol == "GoogleDrive") "google-drive" else protocol.lowercase()
                val target = File("build/network-content-ui-evidence/add-" + name + "-documentation.png")
                target.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
            }
        }
    }

    private fun LazyGridScope.exampleForm(protocol: String) {
        when (protocol) {
            "FTP" -> NetworkFtpForm(
                    name = "Demo storage",
                    onNameChange = {},
                    host = "nas.example.test",
                    onHostChange = {},
                    hostError = false,
                    hostErrorMessage = "",
                    resolvedBaseUrl = "nas.example.test",
                    username = "demo-user",
                    onUsernameChange = {},
                    password = "demo-login-password",
                    onPasswordChange = {},
                    passiveMode = true,
                    onPassiveModeChange = {},
                    ftpsEnabled = false,
                    onFtpsEnabledChange = {},
                    pathEncoding = FtpPathEncoding.Auto,
                    onPathEncodingChange = {}
                )
            "SFTP" -> NetworkSftpForm(
                    name = "Demo storage",
                    onNameChange = {},
                    host = "nas.example.test",
                    onHostChange = {},
                    hostError = false,
                    hostErrorMessage = "",
                    resolvedBaseUrl = "nas.example.test",
                    authType = SftpAuthType.Password,
                    onAuthTypeChange = {},
                    username = "demo-user",
                    onUsernameChange = {},
                    password = "demo-login-password",
                    onPasswordChange = {},
                    privateKey = "",
                    onPrivateKeyChange = {},
                    knownHosts = "",
                    onKnownHostsChange = {}
                )
            "SMB" -> NetworkSmbForm(
                    name = "Demo storage",
                    onNameChange = {},
                    host = "nas.example.test",
                    onHostChange = {},
                    hostError = false,
                    hostErrorMessage = "",
                    resolvedBaseUrl = "nas.example.test",
                    username = "demo-user",
                    onUsernameChange = {},
                    password = "demo-login-password",
                    onPasswordChange = {},
                    shareName = "demo-share",
                    onShareNameChange = {},
                    shareNameError = false,
                    shareNameErrorMessage = "",
                    resolvedShareName = "demo-share",
                    domain = "WORKGROUP",
                    onDomainChange = {}
                )
            "WebDav" -> NetworkWebDavForm(
                    name = "Demo storage",
                    onNameChange = {},
                    host = "https://storage.example.test",
                    onHostChange = {},
                    hostError = false,
                    hostErrorMessage = "",
                    resolvedBaseUrl = "https://storage.example.test",
                    authType = WebDavAuthType.Basic,
                    onAuthTypeChange = {},
                    username = "demo-user",
                    onUsernameChange = {},
                    password = "demo-login-password",
                    onPasswordChange = {},
                    token = "",
                    onTokenChange = {},
                    tokenHeaderName = "Authorization",
                    onTokenHeaderNameChange = {},
                    tokenPrefix = "Bearer",
                    onTokenPrefixChange = {},
                    headers = emptyList(),
                    onHeaderKeyChange = { _, _ -> },
                    onHeaderValueChange = { _, _ -> },
                    onAddHeader = {},
                    onRemoveHeader = {}
                )
            "S3" -> NetworkS3Form(
                    name = "Demo storage",
                    onNameChange = {},
                    endpoint = "https://storage.example.test",
                    onEndpointChange = {},
                    endpointError = false,
                    endpointErrorMessage = "",
                    resolvedEndpoint = "https://storage.example.test",
                    accessKeyId = "demo-access-key",
                    onAccessKeyIdChange = {},
                    accessKeyIdError = false,
                    secretAccessKey = "demo-login-password",
                    onSecretAccessKeyChange = {},
                    secretAccessKeyError = false,
                    sessionToken = "",
                    onSessionTokenChange = {},
                    bucket = "demo-bucket",
                    onBucketChange = {},
                    bucketError = false,
                    region = "eu-central-1",
                    onRegionChange = {},
                    regionError = false,
                    forcePathStyle = false,
                    onForcePathStyleChange = {}
                )
            else -> {
                val cloud = protocol != "NFS"
                val values = StorageFormValues(name = "Demo storage", server = if (protocol == "NFS") "nas.example.test" else "https://cloud.example.test",
                    account = if (cloud) "demo-account" else "", connection = "demo-connection", root = if (protocol in listOf("GoogleDrive", "Dropbox")) "" else "/Demo",
                    library = "f05ab1e5-aeab-43f9-b123-508994e63cba", export = "/exports/demo", uid = "1000", gid = "1000")
                val accounts = if (cloud) listOf(StorageAccount("demo-account", StorageAccountKind.valueOf(protocol),
                    "Demo account", username = "demo@example.test", server = if (protocol in listOf("Nextcloud", "Seafile")) values.server else ""))
                    else emptyList()
                NetworkStorageForm(protocol, values, {}, "", {}, "", {}, accounts,
                    if (protocol == "Seafile") listOf(StorageLibrary(values.library, "Demo library")) else emptyList(),
                    null, false, null)
            }
        }
    }
}
