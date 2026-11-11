package com.folderspan.service.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentHistoryTest {
    private val vaultId = Uuid.random().toString()
    private val root = StorageContentCommit(vaultId = vaultId, operationId = vaultId, entryId = vaultId,
        parentId = "", name = "", kind = StorageContentCommitKind.Root, ciphertextDigest = "ab".repeat(32))
    private fun file(name: String) = StorageContentCommit(vaultId = vaultId, operationId = Uuid.random().toString(),
        entryId = Uuid.random().toString(), parentId = vaultId, name = name, kind = StorageContentCommitKind.File,
        objectId = Uuid.random().toString(), ciphertextLength = 160, ciphertextDigest = "cd".repeat(32))
    private fun revision(file: StorageContentCommit) = file.copy(operationId = Uuid.random().toString(),
        parents = listOf(file.operationId), objectId = Uuid.random().toString())

    @Test
    fun independentEntriesMergeInEitherOrderAndSameEntryBranchesRemainVisible() {
        val a = file("a")
        val b = file("b")
        for (input in listOf(listOf(root, a, b), listOf(b, a, root))) {
            assertEquals(setOf("a", "b"), StorageContentHistory.replay(vaultId, input).liveEntries().map { it.name }.toSet())
        }
        val first = revision(a)
        val second = revision(a)
        val history = StorageContentHistory.replay(vaultId, listOf(second, root, first, a))
        assertEquals(setOf(first.operationId, second.operationId), history.heads.getValue(a.entryId).map { it.operationId }.toSet())
        assertFailsWith<StorageContentRevisionConflictException> { history.head(a.entryId) }
        val merged = revision(first).copy(parents = listOf(first.operationId, second.operationId))
        assertEquals(merged, StorageContentHistory.replay(vaultId, listOf(root, a, first, second, merged)).head(a.entryId))
    }

    @Test
    fun deletionOnlyConsumesObservedRevisionsAndCannotHideAConcurrentWrite() {
        val a = file("a")
        val updated = revision(a)
        val deletion = a.copy(operationId = Uuid.random().toString(), parents = listOf(a.operationId),
            kind = StorageContentCommitKind.Delete, objectId = "", ciphertextLength = 0,
            ciphertextDigest = "", size = 0)
        val history = StorageContentHistory.replay(vaultId, listOf(root, a, updated, deletion))
        assertEquals(setOf(updated.operationId, deletion.operationId), history.heads.getValue(a.entryId).map { it.operationId }.toSet())
        assertFailsWith<StorageContentRevisionConflictException> { history.liveEntries() }
        assertEquals(emptyList(), StorageContentHistory.replay(vaultId, listOf(root, a, deletion)).liveEntries())
    }

    @Test
    fun missingParentsCyclesDuplicateOperationsAndCrossEntryParentsFailClosed() {
        val a = file("a")
        val b = file("b")
        val first = revision(a)
        val second = revision(first)
        val cycle = first.copy(parents = listOf(second.operationId))
        for (records in listOf(
            listOf(root, first), listOf(root, a, a), listOf(root, a, b.copy(parents = listOf(a.operationId))),
            listOf(root, a, cycle, second), listOf(a),
        )) assertFails { StorageContentHistory.replay(vaultId, records) }
    }

    @Test
    fun directoryRenamePreservesChildIdentityAndDeletedParentsCannotHideLiveChildren() {
        val directory = file("before").copy(kind = StorageContentCommitKind.Directory,
            objectId = "", ciphertextLength = 0, ciphertextDigest = "", size = 0)
        val child = file("child").copy(parentId = directory.entryId)
        val renamed = directory.copy(operationId = Uuid.random().toString(), name = "after", parents = listOf(directory.operationId))
        val history = StorageContentHistory.replay(vaultId, listOf(root, directory, child, renamed))
        assertEquals("/after/child", history.path(child))
        assertEquals(child.entryId, history.head(child.entryId)?.entryId)
        val deletion = renamed.copy(operationId = Uuid.random().toString(), kind = StorageContentCommitKind.Delete,
            parents = listOf(renamed.operationId))
        assertFails { StorageContentHistory.replay(vaultId, listOf(root, directory, child, renamed, deletion)).liveEntries() }
    }

    @Test
    fun independentEntriesWithSameNameRequireExplicitConflictHandling() {
        assertFails {
            StorageContentHistory.replay(vaultId, listOf(root, file("same"), file("same"))).liveEntries()
        }
    }
}
