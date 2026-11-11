package com.folderspan.ui.screen.network.form

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.network.Network
import com.folderspan.service.network.StorageContentLocationStatus
import com.folderspan.ui.components.grid.GridList
import kotlinx.coroutines.runBlocking
import strings.AppStrings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StorageContentSectionTest {
    @Test
    fun switchingOnShowsOneInlinePasswordAndGeneratorAcrossWindowSizes() {
        for ((width, height) in listOf(360 to 720, 900 to 360, 1600 to 900)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val operations = PasswordContentOperations()
                val state = StorageContentFormState("FTP", null, mutableStateOf(false), operations)
                setContent {
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            GridList(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), fixedColumnCount = 1) {
                                item { StorageContentSection(state) }
                            }
                        }
                    }
                }
                onNodeWithTag("storage-content-password-input").assertDoesNotExist()
                onNodeWithTag("storage-content-toggle").assertIsEnabled().assertIsOff().performClick()
                onNodeWithTag("storage-content-password-input").assertIsDisplayed().performTextInput("content password")
                onNodeWithTag("storage-content-password-input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                onNodeWithTag("storage-content-password-confirmation").assertDoesNotExist()
                onNodeWithTag("storage-content-password-dialog").assertDoesNotExist()
                onNodeWithTag("storage-content-generate-password").performScrollTo().assertIsEnabled().performClick()
                runOnIdle { assertEquals(1024, state.password.length) }
                onNodeWithTag("storage-content-toggle").assertIsOn()
                assertTrue(operations.events.isEmpty())
                val evidence = File("build/network-content-ui-evidence/inline-password-${width}x${height}.png")
                evidence.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence)
                onNodeWithTag("storage-content-toggle").performClick()
                onNodeWithTag("storage-content-password-input").assertDoesNotExist()
                assertEquals("", state.password)
            }
        }
    }

    @Test
    fun offlineFormKeepsPasswordInItsFieldUntilSaving() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val operations = PasswordContentOperations().apply { inspectFailure = java.io.IOException("offline") }
        val state = passwordTestState(operations)
        runBlocking { state.inspect() }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { StorageContentSection(state, Modifier.padding(16.dp)) }
            }
        }
        onNodeWithTag("storage-content-toggle").performClick()
        onNodeWithTag("storage-content-password-input").performTextInput("offline content password")
        onNodeWithTag("storage-content-generate-password").assertIsEnabled()
        onNodeWithTag("storage-content-password-dialog").assertDoesNotExist()
        assertEquals("offline content password", state.password)
        assertEquals(listOf("inspect"), operations.events)
    }

    @Test
    fun editingConnectionDetailsNeverClearsOrDisablesTheInlinePassword() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val operations = PasswordContentOperations()
        var network by mutableStateOf<Network?>(null)
        lateinit var state: StorageContentFormState
        setContent {
            MaterialTheme {
                state = rememberStorageContentFormState("FTP", network, operations = operations)
                StorageContentSection(state)
            }
        }
        onNodeWithTag("storage-content-toggle").assertIsEnabled().performClick()
        onNodeWithTag("storage-content-password-input").performTextInput("independent password")
        lateinit var original: StorageContentFormState
        runOnIdle {
            original = state
            network = passwordTestNetwork()
        }
        runOnIdle {
            assertSame(original, state)
            assertEquals("independent password", state.password)
            network = Network("Other", "/", "FTP", "other.example.test", "other-user", "other-login")
        }
        runOnIdle {
            assertSame(original, state)
            assertEquals("independent password", state.password)
            assertTrue(state.enabled)
            assertEquals("other.example.test", state.config?.host)
        }
        onNodeWithTag("storage-content-password-input").assertIsEnabled()
        onNodeWithTag("storage-content-toggle").assertIsOn()
    }

    @Test
    fun emptyAndShortPasswordErrorsAppearUnderTheInlineFieldWhenSaving() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val state = passwordTestState(PasswordContentOperations())
        setContent { MaterialTheme { StorageContentSection(state) } }
        onNodeWithTag("storage-content-toggle").performClick()
        runOnIdle {
            runBlocking { assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Empty password") } } }
        }
        onNodeWithText(AppStrings.storage_content_password_required).assertIsDisplayed()
        onNodeWithTag("storage-content-password-input").performTextInput("short")
        runOnIdle {
            runBlocking { assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Short password") } } }
        }
        onNodeWithText(AppStrings.storage_content_password_length).assertIsDisplayed()
        onNodeWithTag("storage-content-generate-password").performClick()
        assertNull(state.validationMessage)
        assertEquals(1024, state.password.length)
    }

    @Test
    fun existingLocationUsesTheSameInlineFieldWithoutConfirmation() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val operations = PasswordContentOperations().apply { location = location.copy(reference = locationReference) }
        val state = passwordTestState(operations, existing = true)
        runBlocking { state.inspect() }
        setContent { MaterialTheme { StorageContentSection(state) } }
        onNodeWithTag("storage-content-toggle").performClick()
        onNodeWithTag("storage-content-password-input").performTextInput("x")
        onNodeWithTag("storage-content-password-confirmation").assertDoesNotExist()
        onNodeWithTag("storage-content-password-dialog").assertDoesNotExist()
        runOnIdle { runBlocking { state.save(passwordTestNetwork()) {} } }
        assertEquals("x", operations.receivedPassword)
        assertEquals("", state.password)
        assertEquals(listOf("inspect", "unlock"), operations.events)
    }

    @Test
    fun newFormsKeepSeparateStableOwnersAndEditsReuseTheSavedOwner() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val firstOps = PasswordContentOperations()
        val secondOps = PasswordContentOperations()
        lateinit var first: StorageContentFormState
        lateinit var second: StorageContentFormState
        var existing by mutableStateOf<Network?>(null)
        setContent {
            val saved = existing
            first = rememberStorageContentFormState("FTP", saved ?: passwordTestNetwork(), saved, firstOps)
            second = rememberStorageContentFormState("FTP", passwordTestNetwork(), operations = secondOps)
        }
        waitUntil { first.location != null && second.location != null }
        lateinit var owner: String
        runOnIdle {
            owner = first.config!!.extras.cloud.connectionId
            assertTrue(owner.isNotBlank())
            assertNotEquals(owner, second.config!!.extras.cloud.connectionId)
            runBlocking {
                first.changeEnabled(true)
                first.editPassword("content password")
                first.save(passwordTestNetwork()) { existing = it }
            }
        }
        runOnIdle { assertEquals(owner, first.config!!.extras.cloud.connectionId) }
    }
}
