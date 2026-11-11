package com.folderspan.ui.screen.network.form

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.service.network.*
import com.folderspan.service.webui.WebUiAttachment
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import strings.AppStrings
import java.io.IOException
import kotlin.test.*

internal class PasswordContentOperations(
    var location: StorageContentLocationStatus = StorageContentLocationStatus(available = true, writable = true),
) : StorageContentOperations by StorageContentPlatform {
    val events = mutableListOf<String>()
    val locationReference = StorageContentLocationReference(root = StoragePath("/"), vaultId = "f05ab1e5-aeab-43f9-b123-508994e63cba")
    var unlocked = false
    var receivedPassword = ""
    var setupConnectionId = ""
    var setupFailure: Throwable? = null
    var unlockFailure: Throwable? = null
    var inspectFailure: Throwable? = null
    var inspectAction: (suspend () -> Unit)? = null
    var setupAction: (suspend () -> Unit)? = null
    var statusAction: (suspend () -> Unit)? = null

    override suspend fun inspectPasswordLocation(config: NetworkConnectionConfig): Result<StorageContentLocationStatus> {
        events += "inspect"
        inspectAction?.invoke()
        return inspectFailure?.let { Result.failure(it) } ?: Result.success(location)
    }
    override suspend fun status(config: NetworkConnectionConfig): Result<StorageContentStatus> {
        statusAction?.invoke()
        return Result.success(StorageContentStatus("test", enabled = config.extras.contentEncryption?.enabled == true,
            unlocked = unlocked, hasKey = true, available = true, metadataProtectionAvailable = true))
    }
    override suspend fun setupPassword(config: NetworkConnectionConfig, password: CharArray): Result<StorageContentLocationReference> {
        events += "setup"
        setupConnectionId = config.extras.cloud.connectionId
        receivedPassword = password.concatToString()
        setupAction?.invoke()
        return setupFailure?.let { Result.failure(it) } ?: Result.success(locationReference).also { unlocked = true }
    }
    override suspend fun unlockPassword(config: NetworkConnectionConfig, password: CharArray): Result<Unit> {
        events += "unlock"
        receivedPassword = password.concatToString()
        return unlockFailure?.let { Result.failure(it) } ?: Result.success(Unit).also { unlocked = true }
    }
    override suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit> {
        events += "enabled:$enabled"
        return Result.success(Unit)
    }
    override suspend fun lock(config: NetworkConnectionConfig): Result<Unit> {
        events += "lock"
        unlocked = false
        return Result.success(Unit)
    }
}

internal fun passwordTestNetwork(reference: StorageContentLocationReference? = null) =
    Network("Server", "/", "FTP", "nas.example.test", "demo", "connection-password",
        extras = NetworkDriveExtras(contentEncryption = reference))

internal fun passwordTestState(operations: PasswordContentOperations, existing: Boolean = false): StorageContentFormState {
    val network = passwordTestNetwork(if (existing) operations.locationReference else null)
    return StorageContentFormState("FTP", network.connectionConfig(), mutableStateOf(false), operations)
}

class StorageContentFormStateTest {
    @Test
    fun passwordCanBeEnteredBeforeAnyConnectionFieldsAndSurvivesTheirChanges() {
        val state = StorageContentFormState("FTP", null, mutableStateOf(false), PasswordContentOperations())
        state.changeEnabled(true)
        state.editPassword("independent content password")
        for (network in listOf(passwordTestNetwork(),
            Network("Other", "/", "FTP", "other.example.test", "other-user", "other-login"))) {
            state.updateConfig(network.connectionConfig())
            assertTrue(state.enabled)
            assertEquals("independent content password", state.password)
            assertNull(state.status)
        }
        state.updateConfig(null)
        assertTrue(state.enabled)
        assertEquals("independent content password", state.password)
    }

    @Test
    fun generatedPasswordReplacesTheInputWithTheMaximumLengthAndSavesWithoutTruncation() = runBlocking {
        val operations = PasswordContentOperations()
        val state = passwordTestState(operations)
        state.changeEnabled(true)
        state.editPassword("old")
        val generated = mutableSetOf<String>()
        repeat(20) {
            state.generatePassword()
            assertEquals(1024, state.password.length)
            assertTrue(state.password.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#${'$'}%^&*()-_=+" })
            generated += state.password
        }
        assertEquals(20, generated.size)
        assertNull(state.validationMessage)
        val password = state.password
        state.save(passwordTestNetwork()) {}
        assertEquals(password, operations.receivedPassword)
    }

    @Test
    fun slowInspectionDoesNotBlockOfflineInputOrSave() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = PasswordContentOperations().apply {
            inspectAction = { entered.complete(Unit); release.await() }
        }
        val state = passwordTestState(operations)
        val inspection = launch { state.inspect() }
        try {
            entered.await()
            assertTrue(state.inspecting)
            assertFalse(state.busy)
            state.changeEnabled(true)
            state.editPassword("offline content password")
            state.save(passwordTestNetwork()) {
                val reference = assertNotNull(it.extras.contentEncryption)
                assertTrue(reference.enabled)
                assertEquals("", reference.vaultId)
            }
            assertEquals(listOf("inspect"), operations.events)
            assertFalse(state.passwordReady)
        } finally {
            release.complete(Unit)
            inspection.join()
        }
    }

    @Test
    fun offlineSaveKeepsOnlyPendingIntentAndNeverPersistsTheContentPassword() = runBlocking {
        val operations = PasswordContentOperations().apply { inspectFailure = IOException("offline") }
        val state = passwordTestState(operations)
        state.changeEnabled(true)
        state.editPassword("offline content password")
        state.save(passwordTestNetwork()) { saved ->
            operations.events += "persist"
            val reference = assertNotNull(saved.extras.contentEncryption)
            assertTrue(reference.enabled)
            assertEquals("", reference.vaultId)
            assertFalse(Json.encodeToString(NetworkDriveExtras.serializer(), saved.extras).contains("offline content password"))
            assertEquals("connection-password", saved.password)
        }
        assertEquals(listOf("inspect", "persist"), operations.events)
        assertFalse(state.passwordReady)
        assertEquals("", operations.receivedPassword)
    }

    @Test
    fun newPasswordIsValidatedOnlyOnSaveWithoutConfirmationOrNormalization() = runBlocking {
        val operations = PasswordContentOperations()
        val state = passwordTestState(operations)
        state.inspect()
        state.changeEnabled(true)
        assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Invalid password") } }
        assertEquals(AppStrings.storage_content_password_required, state.validationMessage)
        state.editPassword("too short")
        assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Invalid password") } }
        assertEquals(AppStrings.storage_content_password_length, state.validationMessage)
        assertEquals("too short", state.password)
        state.editPassword("  content password  ")
        assertEquals(listOf("inspect"), operations.events)
        state.save(passwordTestNetwork()) {
            operations.events += "persist"
            assertEquals(operations.locationReference.copy(enabled = true), it.extras.contentEncryption)
        }
        assertEquals("  content password  ", operations.receivedPassword)
        assertEquals(listOf("inspect", "setup", "persist"), operations.events)
        assertEquals("", state.password)
    }

    @Test
    fun savedOfflineDraftRequiresPasswordAgainAndUnlocksTheDiscoveredExistingLocation() = runBlocking {
        val operations = PasswordContentOperations().apply { location = location.copy(reference = locationReference) }
        val network = passwordTestNetwork(StorageContentLocationReference(enabled = true))
        val state = StorageContentFormState("FTP", network.connectionConfig(), mutableStateOf(true), operations)
        state.inspect()
        assertTrue(state.existingLocation)
        assertFailsWith<IllegalStateException> { state.save(network) { fail("Unverified draft must stay locked") } }
        state.editPassword("old")
        state.save(network) { assertEquals(operations.locationReference.copy(enabled = true), it.extras.contentEncryption) }
        assertEquals("old", operations.receivedPassword)
        assertEquals(listOf("inspect", "unlock"), operations.events)
    }

    @Test
    fun existingLocationCanBeSavedOfflineWithoutUnlockingOrLosingItsIdentity() = runBlocking {
        val operations = PasswordContentOperations().apply { inspectFailure = IOException("offline") }
        val state = passwordTestState(operations, existing = true)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword("old")
        state.save(passwordTestNetwork()) {
            assertEquals(operations.locationReference.copy(enabled = true), it.extras.contentEncryption)
        }
        assertEquals(listOf("inspect", "inspect"), operations.events)
        assertFalse(state.passwordReady)
        assertEquals("", operations.receivedPassword)
    }

    @Test
    fun reconnectingAnOfflineDraftInitializesOnlyAfterPasswordIsEnteredAgain() = runBlocking {
        val operations = PasswordContentOperations()
        val network = passwordTestNetwork(StorageContentLocationReference(enabled = true))
        val state = StorageContentFormState("FTP", network.connectionConfig(), mutableStateOf(true), operations)
        state.inspect()
        assertFailsWith<IllegalStateException> { state.save(network) { fail("Password is not retained") } }
        state.editPassword("fresh content password")
        state.save(network) { assertEquals(operations.locationReference.copy(enabled = true), it.extras.contentEncryption) }
        assertEquals(listOf("inspect", "setup"), operations.events)
    }

    @Test
    fun setupFailureNeverPersistsAPlaintextConnection() = runBlocking {
        val operations = PasswordContentOperations().apply {
            setupFailure = StorageContentException(StorageContentErrorCode.UncertainResult, "private backend detail")
        }
        val state = passwordTestState(operations)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword("content password")
        assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Must not persist") } }
        assertEquals(AppStrings.storage_content_result_uncertain, state.message)
        assertEquals("", state.password)
    }

    @Test
    fun localPersistenceFailureKeepsInitializedReferenceAndRetriesWithoutSetup() = runBlocking {
        val operations = PasswordContentOperations()
        val state = passwordTestState(operations)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword("content password")
        assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { error("database failed") } }
        assertEquals(operations.locationReference, state.reference)
        state.save(passwordTestNetwork()) { operations.events += "persist" }
        assertEquals(listOf("inspect", "setup", "enabled:true", "persist"), operations.events)
    }

    @Test
    fun existingLocationAcceptsItsOriginalShortPasswordOnSave() = runBlocking {
        val operations = PasswordContentOperations().apply { location = location.copy(reference = locationReference) }
        val state = passwordTestState(operations, existing = true)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword(" x ")
        assertEquals("", operations.receivedPassword)
        state.save(passwordTestNetwork()) { operations.events += "persist" }
        assertEquals(" x ", operations.receivedPassword)
        assertEquals("", state.password)
        assertEquals(listOf("inspect", "unlock", "persist"), operations.events)
    }

    @Test
    fun failedUnlockUsesLocalizedErrorAndNeverPersists() = runBlocking {
        val operations = PasswordContentOperations().apply {
            location = location.copy(reference = locationReference)
            unlockFailure = StorageContentException(StorageContentErrorCode.UnlockFailed, "private backend detail")
        }
        val state = passwordTestState(operations, existing = true)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword("wrong")
        assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Wrong password") } }
        assertEquals(AppStrings.storage_content_password_unlock_failed, state.message)
        assertEquals("", state.password)
    }

    @Test
    fun switchingOffOrLeavingThePageDestroysThePasswordDraft() = runBlocking<Unit> {
        val state = passwordTestState(PasswordContentOperations())
        state.changeEnabled(true)
        state.editPassword("temporary password")
        state.changeEnabled(false)
        assertFalse(state.enabled)
        assertEquals("", state.password)
        state.changeEnabled(true)
        state.editPassword("temporary password")
        state.invalidate()
        assertEquals("", state.password)
        assertFailsWith<CancellationException> { state.save(passwordTestNetwork()) { fail("Stale form") } }
    }

    @Test
    fun lateSetupAfterTargetChangeLocksOldReferenceWithoutPersistingOrClearingNewInput() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = PasswordContentOperations().apply {
            setupAction = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        }
        val state = passwordTestState(operations)
        state.inspect()
        state.changeEnabled(true)
        state.editPassword("content password")
        val save = launch { state.save(passwordTestNetwork()) { fail("Stale target must not persist") } }
        entered.await()
        state.updateConfig(Network("Other", "/", "FTP", "other.example.test", "user", "login").connectionConfig())
        release.complete(Unit)
        save.join()
        assertTrue(save.isCancelled)
        assertEquals("content password", state.password)
        assertEquals(listOf("inspect", "setup", "lock"), operations.events)
    }

    @Test
    fun lateStatusFromTheOldAddressCannotReplaceTheNewTargetsState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = PasswordContentOperations().apply {
            location = location.copy(reference = locationReference)
            statusAction = { entered.complete(Unit); release.await() }
        }
        val state = passwordTestState(operations, existing = true)
        state.changeEnabled(true)
        state.editPassword("independent password")
        val inspection = launch { state.inspect() }
        entered.await()
        state.updateConfig(Network("Other", "/", "FTP", "other.example.test", "user", "login").connectionConfig())
        release.complete(Unit)
        inspection.join()
        assertTrue(inspection.isCancelled)
        assertNull(state.location)
        assertNull(state.reference)
        assertNull(state.status)
        assertEquals("independent password", state.password)
    }

    @Test
    fun readOnlyUnavailableAndDamagedLocationsCannotBeSavedAsEnabled() = runBlocking {
        val operations = listOf(
            PasswordContentOperations(StorageContentLocationStatus(available = false)),
            PasswordContentOperations(StorageContentLocationStatus(available = true, writable = false)),
            PasswordContentOperations().apply {
                inspectFailure = StorageContentException(StorageContentErrorCode.DamagedLocation, "damaged")
            },
        )
        for (operation in operations) {
            val state = passwordTestState(operation)
            state.changeEnabled(true)
            state.editPassword("content password")
            state.inspect()
            assertFailsWith<IllegalStateException> { state.save(passwordTestNetwork()) { fail("Must not persist") } }
            assertFalse(operation.events.contains("setup"))
        }
    }

    @Test
    fun testingConnectionOnlyReadsWithoutInitializingOrClearingPassword() = runBlocking {
        val operations = PasswordContentOperations()
        val draft = passwordTestState(operations)
        draft.inspect()
        draft.changeEnabled(true)
        draft.editPassword("content password")
        val fake = object : Network("Server", "/", "FTP", "nas.example.test", "demo", "connection-password") {
            override suspend fun getList(path: String, requestId: String?, batchId: String?): Result<List<FileSimpleInfo>> {
                operations.events += "connection-read"
                return Result.success(emptyList())
            }
            override suspend fun close() { operations.events += "connection-close" }
        }
        val snackbar = SnackbarHostState()
        val form = StorageFormState("FTP", mutableStateOf(StorageFormValues(server = "nas.example.test", username = "demo")),
            mutableStateOf("connection-password"), false, this, snackbar, networkFactory = { _, _, _ -> fake }) {
            fail("Testing must never persist")
        }
        form.content = draft
        form.testConnection()
        withTimeout(5000) { while (snackbar.currentSnackbarData == null) yield() }
        snackbar.currentSnackbarData?.dismiss()
        withTimeout(5000) { while (form.busy) yield() }
        assertEquals(listOf("inspect", "connection-read", "inspect", "connection-close"), operations.events)
        assertEquals("content password", draft.password)
    }

    @Test
    fun runtimeReconnectDiscardsPasswordBeforeAnyWrite() = runBlocking {
        val operations = PasswordContentOperations()
        val state = passwordTestState(operations)
        state.changeEnabled(true)
        state.editPassword("content password")
        WebUiAttachment.reset()
        assertFailsWith<CancellationException> { state.save(passwordTestNetwork()) { fail("Stale Runtime") } }
        assertEquals("", state.password)
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun remoteReferencesFollowTheirLocationsWithoutBindingThePasswordDraft() {
        val reference = PasswordContentOperations().locationReference
        val old = passwordTestNetwork(reference)
        val renamed = Network("New name", old.pathSeparator, old.protocol, old.host, old.username, old.password)
        assertEquals(reference, storageContentConfigForForm(renamed, old)?.extras?.contentEncryption)
        val other = Network("Other", "/", "FTP", "other.example.test", "demo", "connection-password")
        assertNull(storageContentConfigForForm(other, old)?.extras?.contentEncryption)
        val cloud = CloudDriveExtras(accountId = "old-account", connectionId = "connection", rootId = "root")
        val oldCloud = Network("Drive", "/", "GoogleDrive", "", "", "",
            extras = NetworkDriveExtras(cloud = cloud, contentEncryption = reference))
        val otherAccount = Network("Drive", "/", "GoogleDrive", "", "", "",
            extras = NetworkDriveExtras(cloud = cloud.copy(accountId = "new-account")))
        assertNull(storageContentConfigForForm(otherAccount, oldCloud)?.extras?.contentEncryption)
    }
}
