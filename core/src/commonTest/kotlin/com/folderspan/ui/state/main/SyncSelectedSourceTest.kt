package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageReference
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class SyncSelectedSourceTest {
    private fun selected(id: String = "old") = FileSimpleInfo.pathFileSimpleInfo("/parent/a.txt").withCopy(
        name = "a.txt", protocol = FileProtocol.Network, protocolId = "drive", isDirectory = false,
        size = 7, updatedDate = 10_000, storageReference = StorageReference(id, "parent"))

    @Test fun committedReplacementRebindsLocallyWithoutChangingTheTaskSelection() = runTest {
        val original = selected()
        val current = selected("new")
        val previous = SyncBaseline("a.txt", mapOf("source" to current.syncEntryState()))
        val (effective, fresh) = resolveSyncSelectedFile(original, previous, "/") { parent ->
            assertEquals("/parent", parent.path)
            assertEquals(StorageReference(id = "parent"), parent.storageReference)
            parent to listOf(current)
        }
        assertEquals("new", effective.storageReference!!.id)
        assertEquals(current, fresh)
        assertEquals("old", original.storageReference!!.id)
    }

    @Test fun deletedFilesDoNotFollowTheOldSelectedIdIntoRecycle() = runTest {
        val (effective, missing) = resolveSyncSelectedFile(selected(), null, "/") { it to emptyList() }
        assertEquals("old", effective.storageReference!!.id)
        assertNull(missing)
        val recorded = StorageReference("new", "parent")
        val deleted = SyncBaseline("a.txt", mapOf("source" to null), selectedSourceReference = recorded)
        val (restoredSelection, restored) = resolveSyncSelectedFile(selected(), deleted, "/") { it to listOf(selected("new")) }
        assertEquals(recorded, restoredSelection.storageReference)
        assertEquals("new", restored!!.storageReference!!.id)
    }

    @Test fun outOfOrderPortableAndLocalReferencesStillResolveOnlyAKnownCanonicalId() = runTest {
        for ((local, configured) in listOf("previous" to "new", "new" to "previous")) {
            val baseline = SyncBaseline("a.txt", mapOf("source" to selected(local).syncEntryState()),
                selectedSourceReference = selected(local).storageReference)
            val (effective, fresh) = resolveSyncSelectedFile(selected(), baseline, "/", selected(configured).storageReference) {
                it to listOf(selected("new"))
            }
            assertEquals("new", effective.storageReference!!.id)
            assertEquals("new", fresh!!.storageReference!!.id)
            assertFailsWith<IllegalStateException> {
                resolveSyncSelectedFile(selected(), baseline, "/", selected(configured).storageReference) { it to listOf(selected("foreign")) }
            }
        }
    }

    @Test fun unrelatedSameNameIdsAmbiguousNamesChangedKindsAndLinksRequireReselection() = runTest {
        for (files in listOf(listOf(selected("unrelated")), listOf(selected(), selected("duplicate")),
            listOf(selected().withCopy(isDirectory = true)), listOf(selected().withCopy(isSymbolicLink = true)),
            listOf(selected().copy(path = "/outside/a.txt")),
            listOf(selected().copy(storageReference = StorageReference("old", "outside"))))) {
            assertFailsWith<Exception> { resolveSyncSelectedFile(selected(), null, "/") { it to files } }
        }
    }

    @Test fun recordedBindingsCannotLeaveTheSelectedParentOrReferToAnotherAlias() = runTest {
        val moved = selected("new").copy(storageReference = StorageReference("new", "outside"))
        for (baseline in listOf(SyncBaseline("a.txt", mapOf("source" to moved.syncEntryState())),
            SyncBaseline("different.txt", mapOf("source" to selected("new").syncEntryState())),
            SyncBaseline("a.txt", mapOf("source" to selected().withCopy(isDirectory = true).syncEntryState())))) {
            var lists = 0
            assertFailsWith<IllegalArgumentException> { resolveSyncSelectedFile(selected(), baseline, "/") { lists++; it to emptyList() } }
            assertEquals(0, lists)
        }
    }

    @Test fun portableReferencesKeepLogicalSelectionAndPreviewStableButReselectionUsesANewKey() {
        val original = SyncTask(1, "selected", sourceType = SyncEndpointType.Network, sourceRef = "drive",
            sourcePath = "/parent/a.txt", sourceStorageReference = selected().storageReference,
            targetType = SyncEndpointType.Local, targetPath = "/target", selectedSources = listOf(selected()))
        val current = original.copy(currentSourceReferences = mapOf(syncSelectedSourceKey(selected()) to selected("new").storageReference!!),
            sourceStorageReference = selected("new").storageReference)
        assertEquals(syncDefinitionFingerprint(original), syncDefinitionFingerprint(current))
        assertEquals(syncPreviewFingerprint(original), syncPreviewFingerprint(current))
        assertEquals("new", current.resolvedSelectedSources().single().storageReference!!.id)
        assertEquals("old", current.selectedSources.single().storageReference!!.id)
        val reselected = current.copy(selectedSources = listOf(selected("different")))
        assertEquals("different", reselected.resolvedSelectedSources().single().storageReference!!.id)
        assertNotEquals(syncDefinitionFingerprint(current), syncDefinitionFingerprint(reselected))
        assertFailsWith<IllegalArgumentException> { current.copy(currentSourceReferences = mapOf(syncSelectedSourceKey(selected()) to
            StorageReference("new", "outside"))).resolvedSelectedSources() }
    }
}
