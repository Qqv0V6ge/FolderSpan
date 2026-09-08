package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.CancellationException

internal class ExternalFileImportCoordinator(
    private val pasteExternalFiles: suspend (
        ExternalFileImportTarget,
        List<FileSimpleInfo>,
        FileOperationState,
        ExternalFileResourceLease?,
    ) -> List<Long>,
) {
    suspend fun import(
        target: ExternalFileImportTarget,
        batch: PreparedExternalFileBatch,
        fileOperationState: FileOperationState,
    ): ExternalFileImportResult {
        if (batch.files.isEmpty()) {
            batch.lease?.let { lease -> ExternalFileResourceLeaseRegistry.releaseProducer(lease.id) }
            return ExternalFileImportResult(
                skipped = batch.skipped,
                failure = if (batch.skipped.isEmpty()) {
                    ExternalFileImportFailure.NoFiles
                } else {
                    ExternalFileImportFailure.AllSkipped
                },
            )
        }
        if (!target.destination.isDirectory || target.destination.path != target.capturedPath) {
            batch.lease?.let { lease -> ExternalFileResourceLeaseRegistry.releaseProducer(lease.id) }
            return ExternalFileImportResult(
                skipped = batch.skipped,
                failure = ExternalFileImportFailure.TargetUnavailable,
            )
        }

        val taskKeys = try {
            pasteExternalFiles(
                target,
                batch.files,
                fileOperationState,
                batch.lease,
            )
        } catch (error: Throwable) {
            batch.lease?.let { lease -> ExternalFileResourceLeaseRegistry.releaseProducer(lease.id) }
            if (error is CancellationException) throw error
            return ExternalFileImportResult(
                skipped = batch.skipped,
                failure = if (error is ExternalFileTargetUnavailableException) {
                    ExternalFileImportFailure.TargetUnavailable
                } else {
                    ExternalFileImportFailure.ReadFailed
                },
            )
        }
        if (taskKeys.isEmpty()) {
            batch.lease?.let { lease -> ExternalFileResourceLeaseRegistry.releaseProducer(lease.id) }
            return ExternalFileImportResult(
                skipped = batch.skipped,
                failure = ExternalFileImportFailure.AllSkipped,
            )
        }
        return ExternalFileImportResult(
            taskKeys = taskKeys,
            acceptedCount = taskKeys.size,
            skipped = batch.skipped,
        )
    }
}
