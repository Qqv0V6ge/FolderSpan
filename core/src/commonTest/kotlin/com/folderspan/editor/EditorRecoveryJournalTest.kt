package com.folderspan.editor

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorRecoveryJournalTest {
    @Test
    fun dirtySessionIsOfferedAndCanBeRestoredOrDiscarded() = runSuspendTest {
        val store = MemoryRecoveryStore()
        val source = RecordingFileEditorContentSource("original".encodeToByteArray())
        val first = FileEditorDocument(
            source = source,
            editorSessionKey = "local:file",
            recoveryJournalStore = store,
        )

        assertTrue(first.initialize().isSuccess)
        assertTrue(first.unlockEditing(confirmed = true).isSuccess)
        assertTrue(first.updateText("recovered").isSuccess)
        first.close()
        assertTrue(store.value != null)

        val restored = FileEditorDocument(
            source = source,
            editorSessionKey = "local:file",
            recoveryJournalStore = store,
        )
        assertTrue(restored.initialize().isSuccess)
        assertTrue(restored.state.value.recoveryAvailable)
        assertFalse(restored.state.value.recoverySourceChanged)
        assertTrue(restored.restoreRecovery().isSuccess)
        assertContentEquals("recovered".encodeToByteArray(), restored.state.value.bytes)
        assertTrue(restored.state.value.dirty)
        assertFalse(restored.state.value.recoveryAvailable)

        assertTrue(restored.unlockEditing(confirmed = true).isSuccess)
        assertTrue(restored.save(backupEnabled = false).isSuccess)
        assertContentEquals("recovered".encodeToByteArray(), source.bytes())
        assertNull(store.value)
    }

    @Test
    fun changedSourceRecoveryIsReviewOnlyAndMustUseSaveAs() = runSuspendTest {
        val store = MemoryRecoveryStore()
        val source = RecordingFileEditorContentSource("original".encodeToByteArray())
        val first = FileEditorDocument(
            source = source,
            editorSessionKey = "device:file",
            recoveryJournalStore = store,
        )
        assertTrue(first.initialize().isSuccess)
        assertTrue(first.unlockEditing(confirmed = true).isSuccess)
        assertTrue(first.updateText("recovered").isSuccess)
        first.close()
        source.simulateExternalChange("external".encodeToByteArray())

        val restored = FileEditorDocument(
            source = source,
            editorSessionKey = "device:file",
            recoveryJournalStore = store,
        )
        assertTrue(restored.initialize().isSuccess)
        assertTrue(restored.state.value.recoverySourceChanged)
        assertTrue(restored.restoreRecovery().isFailure)
        assertTrue(restored.restoreRecovery(allowChangedSourceForReview = true).isSuccess)
        assertTrue(restored.state.value.recoveryReviewOnly)
        assertTrue(restored.unlockEditing(confirmed = true).isSuccess)
        assertTrue(restored.save(forceOverwriteConfirmed = true, backupEnabled = false).isFailure)
        assertContentEquals("external".encodeToByteArray(), source.bytes())

        val destination = RecordingFileEditorContentSource(byteArrayOf())
        assertTrue(
            restored.saveAs(
                destination = destination,
                sourceChangeConfirmed = true,
            ).isSuccess,
        )
        assertContentEquals("recovered".encodeToByteArray(), destination.bytes())
        assertContentEquals("external".encodeToByteArray(), source.bytes())
    }

    @Test
    fun discardRemovesRecoveryWithoutChangingSource() = runSuspendTest {
        val store = MemoryRecoveryStore().apply {
            value = EditorRecoveryJournal(
                sourceSize = 3L,
                sourceRevision = "1",
                pageSize = DEFAULT_FILE_EDITOR_PAGE_SIZE,
                encoding = EditorTextEncoding.UTF8.name,
                currentPage = 0L,
                pages = emptyList(),
            )
        }
        val source = RecordingFileEditorContentSource("abc".encodeToByteArray())
        val document = FileEditorDocument(
            source = source,
            editorSessionKey = "local:file",
            recoveryJournalStore = store,
        )

        assertTrue(document.initialize().isSuccess)
        assertTrue(document.state.value.recoveryAvailable)
        assertTrue(document.discardRecovery().isSuccess)
        assertNull(store.value)
        assertContentEquals("abc".encodeToByteArray(), source.bytes())
        assertFalse(document.state.value.dirty)
    }

    private class MemoryRecoveryStore : EditorRecoveryJournalStore {
        var value: EditorRecoveryJournal? = null

        override suspend fun read(key: String): Result<EditorRecoveryJournal?> = Result.success(value)

        override suspend fun write(key: String, journal: EditorRecoveryJournal): Result<Unit> {
            value = journal
            return Result.success(Unit)
        }

        override suspend fun remove(key: String): Result<Unit> {
            value = null
            return Result.success(Unit)
        }

        override suspend fun cleanup(maxEntries: Int) = Unit
    }
}
