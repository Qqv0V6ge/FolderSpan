package com.folderspan.service.network

import com.folderspan.settings.MemoryStringSecretVault
import com.folderspan.settings.SensitiveSettings
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference
import kotlin.test.*

class StorageUploadSessionStoreTest {
    @Test
    fun protectedSessionsSurviveReopeningWithoutPlaintextSettings() {
        val plaintext = createInMemorySettings()
        val vault = MemoryStringSecretVault()
        val settings = SensitiveSettings(plaintext, vault)
        val store = StorageUploadSessionStore(settings)
        val reference = reference(store.ownerRuntimeId)
        store.save(7, reference, session())

        val reopened = StorageUploadSessionStore(settings)
        assertEquals(store.ownerRuntimeId, reopened.ownerRuntimeId)
        assertEquals("https://upload.invalid/sensitive", reopened.load(7, reference)?.secret)
        assertTrue(plaintext.keys.none { it.startsWith("auth.storage.upload.") })
        assertEquals(
            setOf("auth.storage.upload.owner", "auth.storage.upload." + reference.sessionId),
            vault.keys(),
        )
    }

    @Test
    fun runtimeTaskAccountAndSourceChangesCannotReuseSession() {
        val store = StorageUploadSessionStore(createInMemorySettings())
        val reference = reference(store.ownerRuntimeId)
        store.save(7, reference, session())
        listOf(
            reference.copy(ownerRuntimeId = "another-runtime"),
            reference.copy(connectionId = "another-account"),
            reference.copy(sourceVersion = "changed-content"),
            reference.copy(source = TaskRuntimeEndpointRef(path = "/other")),
            reference.copy(target = TaskRuntimeEndpointRef(path = "/other-target")),
        ).forEach { changed ->
            assertFailsWith<IllegalStateException> { store.load(7, changed) }
        }
        assertFailsWith<IllegalStateException> { store.load(8, reference) }
        assertFailsWith<IllegalStateException> { store.save(7, reference, session().copy(binding = "another-account")) }
        assertNotNull(store.load(7, reference))
    }

    @Test
    fun taskCleanupPreservesOtherTasksAndUncertainCompletion() {
        val store = StorageUploadSessionStore(createInMemorySettings())
        val first = reference(store.ownerRuntimeId)
        val other = first.copy(sessionId = "019a3bd0-0000-7000-8000-000000000002")
        val uncertain = first.copy(sessionId = "019a3bd0-0000-7000-8000-000000000003")
        store.save(7, first, session())
        store.save(8, other, session())
        store.save(7, uncertain, session().copy(status = StorageUploadStatus.UNCERTAIN))
        store.removeTask(7)
        assertNull(store.load(7, first))
        assertNotNull(store.load(8, other))
        assertEquals(StorageUploadStatus.UNCERTAIN, store.load(7, uncertain)?.status)
    }

    private fun reference(owner: String) = TaskRuntimeUploadSessionReference(
        sessionId = "019a3bd0-0000-7000-8000-000000000001",
        ownerRuntimeId = owner, connectionId = "connection-a",
        source = TaskRuntimeEndpointRef(path = "/source"),
        target = TaskRuntimeEndpointRef(path = "/target"),
        sourceVersion = "sha256:fixed-source",
    )

    private fun session() = StorageUploadSession(
        backend = "fixture", target = StoragePath("/target"), size = 8, confirmedOffset = 0,
        secret = "https://upload.invalid/sensitive", binding = "account-a",
    )
}
