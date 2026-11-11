package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.service.network.storageConnectionBinding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

/** Receipts distinguish this run's earlier writes from changes made after the preview. */
internal class SyncUpdateGuard(
    private val sync: SyncState,
    private val prepared: SyncPreparedRun,
    private val target: SyncUpdateTarget,
    private val operation: Task,
) {
    private val receipts = mutableMapOf<String, FileSimpleInfo?>()
    private val mutex = Mutex()
    private val proofs = target.contentProofs.toMutableMap()
    private val endpoint = target.endpoint
    private val root = target.plan.root.path
    private val separator = sync.endpointFiles.endpointSeparator(endpoint.type, endpoint.desk)
    private val recoveryEndpoint = SyncRecoveryEndpoint(endpoint.type, endpoint.ref, root,
        endpoint.root.storageReference, endpoint.connectionBinding)
    private val io = SyncRecoveryEndpointOperations(sync.endpointFiles, operation, mapOf(recoveryEndpoint to endpoint.desk))

    private fun changed(): Nothing = throw SyncPlannedStateChangedException(AppStrings.sync_preview_keep_changed)

    private suspend fun validateDefinition() {
        if (!sync.isTaskOwnedByCurrentDevice(prepared.task) ||
            sync.getTask(prepared.task.id)?.let(::syncPreviewFingerprint) != prepared.previewFingerprint) changed()
        for (end in prepared.endpoints) {
            val current = sync.resolveEndpoint(end.type, end.ref) ?: changed()
            if (end.connectionBinding.isNotEmpty() && (current !is Network ||
                storageConnectionBinding(current.connectionConfig()) != end.connectionBinding)) changed()
        }
        val source = prepared.endpoints.first()
        val root = sync.endpointFiles.readPathInfo(source.type, source.ref, source.desk, source.root.path, source.root).getOrThrow()
        if (root == null || root.isSymbolicLink || root.isDirectory != source.root.isDirectory || root.storageReference != source.root.storageReference) changed()
        sync.ensureOperationRunning(operation.key)
    }

    private suspend fun fresh(path: String, expected: FileSimpleInfo?): FileSimpleInfo? {
        val actual = sync.endpointFiles.readPathInfo(endpoint.type, endpoint.ref, endpoint.desk, path,
            expected ?: endpoint.root.withCopy(path = path, storageReference = null)).getOrThrow()
        if (actual?.isSymbolicLink == true) changed()
        return actual
    }

    private suspend fun expected(path: String): FileSimpleInfo? =
        mutex.withLock { if (receipts.containsKey(path)) receipts[path] else target.expected[path] }

    private suspend fun checkPath(path: String) {
        val expected = expected(path)
        val actual = fresh(path, expected)
        val proof = mutex.withLock { proofs[path] }
        if (proof != null) {
            if (actual == null || actual.isDirectory || actual.storageReference != expected?.storageReference) changed()
            val state = readVerifiedSyncEndpointState(sync.endpointFiles, endpoint.type, endpoint.ref, endpoint.desk,
                syncComparisonTargetFile(endpoint.desk, actual), operation).getOrThrow()
            if (!syncEntryMatchesSnapshot(proof, state)) changed()
        } else if (expected == null && actual != null || expected != null &&
            !syncEntryMatchesSnapshot(expected.syncEntryState(), actual?.syncEntryState())) changed()
    }

    suspend fun beforeRoot() {
        validateDefinition()
        for (path in target.expected.keys.filter { it == root || syncTargetInsideSourcePath(it, root, separator) }
            .sortedBy { it.length }) checkPath(path)
    }

    suspend fun rootCreated() {
        for (path in target.expected.keys.filter { it == root || syncTargetInsideSourcePath(it, root, separator) }) {
            val actual = fresh(path, null)
            if (actual?.isDirectory != true) changed()
            mutex.withLock { receipts[path] = actual; proofs.remove(path) }
        }
    }

    suspend fun before(item: SyncPlanItem) {
        validateDefinition()
        val source = if (item.action == SyncPlanAction.CreateDirectory) prepared.update!!.sourceDirectories[item.relativePath] ?: changed()
        else if (prepared.task.destinations.isEmpty()) item.source else
            prepared.update!!.source.files.singleOrNull { it.relativePath == item.relativePath }?.file
        if (source != null) {
            val end = prepared.endpoints.first()
            val actual = sync.endpointFiles.readPathInfo(end.type, end.ref, end.desk, source.path, source).getOrThrow()
            if (actual == null || actual.isSymbolicLink || actual.isDirectory != source.isDirectory || actual.storageReference != source.storageReference ||
                !source.isDirectory && (actual.size != source.size || actual.updatedDate != source.updatedDate)) changed()
        }
        var parent = parentPathOf(item.targetPath, separator)
        while (parent != root && syncTargetInsideSourcePath(root, parent, separator)) {
            checkPath(parent)
            parent = parentPathOf(parent, separator)
        }
        checkPath(root)
        checkPath(item.targetPath)
        target.replacementTrees[item.targetPath]?.takeIf { expected(item.targetPath)?.isDirectory == true }?.let { expected ->
            val actual = io.tree(recoveryEndpoint, item.targetPath, expected)
            if (expected.keys != actual.keys || expected.any { (path, state) -> !syncEntryMatchesSnapshot(state, actual[path]) }) changed()
        }
    }

    suspend fun removeDirectory(item: SyncPlanItem) {
        if (expected(item.targetPath)?.isDirectory != true) return
        val tree = checkNotNull(target.replacementTrees[item.targetPath])
        io.remove(recoveryEndpoint, item.targetPath, checkNotNull(tree[""]), tree)
        mutex.withLock { receipts[item.targetPath] = null; proofs.remove(item.targetPath) }
    }

    suspend fun completed(item: SyncPlanItem) {
        val actual = fresh(item.targetPath, null) ?: changed()
        val proof = if (!actual.isDirectory && actual.updatedDate <= 0) readVerifiedSyncEndpointState(sync.endpointFiles,
            endpoint.type, endpoint.ref, endpoint.desk, syncComparisonTargetFile(endpoint.desk, actual), operation).getOrThrow() else null
        mutex.withLock {
            receipts[item.targetPath] = actual
            if (proof == null) proofs.remove(item.targetPath) else proofs[item.targetPath] = proof
        }
    }
}
