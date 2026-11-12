package com.folderspan.ui.screen.network

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.main.network.Network
import com.folderspan.ui.state.file.FileState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.NetworkDriveQueries
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.BackStackAppNavigator
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.toAppRoute
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.test.createInMemorySettings
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import strings.AppStrings
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class NetworkAddInlineFormTest {
    @Test
    fun newProtocolsStayInTheAddPageAndUseItsSaveAction() = withNetworks { networks, queries ->
        val screen = NetworkAddEntryScreen()
        val route = screen.toAppRoute()
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, route))
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppNavigator provides navigator) {
                        screen.Content()
                    }
                }
            }
            fun select(label: String) {
                onNodeWithText(AppStrings.ui_agreement).performScrollTo().performClick()
                onNodeWithText(label).performScrollTo().performClick()
            }
            select(AppStrings.ui_link_sharing)
            onNodeWithText(AppStrings.ui_read_only).assertExists()
            onNodeWithTag("storage-content-toggle").assertDoesNotExist()
            select("NFS")
            onNodeWithText(AppStrings.storage_nfs_export).assertExists()
            onNodeWithText(AppStrings.ui_add_network).assertExists()
            runOnIdle {
                assertSame(route, navigator.currentRoute)
                assertEquals(2, navigator.routes.size)
            }

            select("FTP")
            onNodeWithText(AppStrings.ui_passive_mode).assertExists()
            select(AppStrings.storage_http_directory)
            onNodeWithText(AppStrings.storage_http_read_only).assertExists()
            onNodeWithText(AppStrings.ui_add_network).assertExists()
            runOnIdle { assertSame(route, navigator.currentRoute) }

            select("NFS")
            onNodeWithText(AppStrings.ui_link_address).performTextReplacement("nfs.example.test:2049")
            onNodeWithText(AppStrings.ui_save_database).assertDoesNotExist()
            onNodeWithText(AppStrings.ui_leave_blank_use_arg0.format(arg0 = "nfs.example.test:2049")).assertExists()
            onNodeWithContentDescription(AppStrings.ui_save).performClick()
            waitUntil(timeoutMillis = 5000) { networks.entries.size == 1 }
            runOnIdle {
                assertEquals("NFS", networks.entries.single().network.protocol)
                assertEquals("/", networks.entries.single().network.extras.nfs.exportPath)
                assertEquals("", networks.entries.single().network.extras.cloud.connectionId)
                assertTrue(networks.entries.single().isPersisted)
                assertEquals("nfs.example.test:2049", networks.entries.single().network.name)
                assertEquals(1, queries.selectAll().executeAsList().size)
                assertEquals(listOf(AppRoute.Home), navigator.routes)
            }
        }
    }

    @Test
    fun editingTemporaryConnectionsPersistsThemAndKeepsAutomaticOrCustomNames() {
        for (persisted in listOf(false, true)) for (protocol in listOf("FTP", "SFTP", "NFS", "HttpDirectory")) withNetworks { networks, queries ->
            val host = if (protocol == "HttpDirectory") "https://nas.example.test/files/" else "nas.example.test"
            runBlocking { networks.addNetwork(Network("Old", "/", protocol, host, "", ""), persisted) }
            val originalId = networks.entries.single().id
            val screen = NetworkEditScreen(networks.entries.single())
            val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, screen.toAppRoute()))
            runSkikoComposeUiTest(size = Size(900f, 1400f)) {
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() }
                    }
                }
                onNodeWithText(AppStrings.ui_save_database).assertDoesNotExist()
                onNodeWithText(AppStrings.ui_name).performScrollTo().performTextReplacement(
                    if (protocol == "SFTP") " Custom server " else "")
                val image = onRoot().captureToImage().toAwtImage()
                val evidence = File("build/network-form-ui-evidence/edit-$protocol.png")
                evidence.parentFile.mkdirs()
                ImageIO.write(image, "png", evidence)
                onNodeWithContentDescription(AppStrings.ui_save).performClick()
                waitUntil(timeoutMillis = 5000) {
                    networks.entries.single().isPersisted && navigator.routes == listOf(AppRoute.Home)
                }
                runOnIdle {
                    val saved = networks.entries.single()
                    if (persisted) assertEquals(originalId, saved.id) else assertTrue(saved.id > 0 && originalId < 0)
                    assertEquals(saved.id, queries.selectAll().executeAsOne().id)
                    assertEquals(1, networks.entries.size)
                    assertEquals(if (protocol == "SFTP") "Custom server" else "nas.example.test", queries.selectAll().executeAsOne().name)
                    assertEquals(listOf(AppRoute.Home), navigator.routes)
                }
            }
        }
    }

    @Test
    fun legacyProtocolsSaveWithAutomaticNames() {
        for (protocol in listOf("FTP", "SFTP", "SMB", "WebDav", "S3")) withNetworks { networks, queries ->
            val screen = NetworkAddEntryScreen()
            val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, screen.toAppRoute()))
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() }
                    }
                }
                if (protocol != "FTP") {
                    onNodeWithText(AppStrings.ui_agreement).performScrollTo().performClick()
                    onNodeWithText(protocol).performScrollTo().performClick()
                }
                val hostLabel = if (protocol == "S3") AppStrings.network_endpoint_label else AppStrings.ui_link_address
                val host = when (protocol) {
                    "SMB" -> "smb://nas.example.test/share"
                    "WebDav", "S3" -> "https://nas.example.test/files/"
                    else -> "nas.example.test"
                }
                onNodeWithText(hostLabel).performScrollTo().performTextReplacement(host)
                if (protocol == "S3") {
                    for ((label, value) in listOf(AppStrings.network_access_key_id_label to "access", AppStrings.network_secret_access_key_label to "secret",
                        AppStrings.network_bucket_label to "bucket", AppStrings.network_region_label to "region")) {
                        onNodeWithText(label).performScrollTo().performTextReplacement(value)
                    }
                }
                onNodeWithText(AppStrings.ui_save_database).assertDoesNotExist()
                onNodeWithContentDescription(AppStrings.ui_save).performClick()
                try {
                    waitUntil(timeoutMillis = 5000) { networks.entries.size == 1 }
                } catch (failure: ComposeTimeoutException) {
                    throw AssertionError("Saving $protocol failed:\n${onRoot().printToString()}", failure)
                }
                runOnIdle {
                    assertTrue(networks.entries.single().isPersisted)
                    assertEquals("", networks.entries.single().network.extras.cloud.connectionId)
                    assertEquals("nas.example.test", queries.selectAll().executeAsOne().name)
                }
            }
        }
    }

    @Test
    fun captureAllWritableProtocolFormsWithoutCredentials() = withNetworks { _, _ ->
        val screen = NetworkAddEntryScreen()
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, screen.toAppRoute()))
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() }
                }
            }
            for (protocol in listOf("FTP", "SFTP", "SMB", "WebDav", "S3", "NFS", "Google Drive", "Dropbox", "Nextcloud", "Seafile")) {
                if (protocol != "FTP") {
                    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                    onNodeWithText(AppStrings.ui_agreement).performClick()
                    onNodeWithText(protocol).performScrollTo().performClick()
                }
                onNodeWithText(AppStrings.ui_save_database).assertDoesNotExist()
                onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                val evidence = File("build/network-content-ui-evidence/add-" + protocol.lowercase().replace(" ", "-") + ".png")
                evidence.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence)
                onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("storage-content-section"))
                onNode(hasScrollToIndexAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 10000f) }
                onNodeWithTag("storage-content-section").assertIsDisplayed()
                val toggle = onNodeWithTag("storage-content-toggle").assertIsOff().assertIsEnabled()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                    File("build/network-content-ui-evidence/add-" + protocol.lowercase().replace(" ", "-") + "-content.png"))
                if (protocol == "FTP") {
                    toggle.performClick().assertIsOn()
                    onNodeWithTag("storage-content-password-input").assertExists()
                    toggle.performClick().assertIsOff()
                }
            }
        }
    }

    private fun withNetworks(block: (NetworkState, NetworkDriveQueries) -> Unit) {
        DataEncryptionKey.init(createInMemorySettings())
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        val queries = NetworkDriveQueries(driver)
        val database = Proxy.newProxyInstance(
            FolderSpanDatabase::class.java.classLoader,
            arrayOf(FolderSpanDatabase::class.java),
        ) { _, method, _ ->
            check(method.name == "getNetworkDriveQueries")
            queries
        } as FolderSpanDatabase
        val networks = NetworkState(database)
        startKoin { modules(module {
            single { networks }
            single { MainState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try { block(networks, queries) }
        finally { stopKoin(); driver.close() }
    }
}
