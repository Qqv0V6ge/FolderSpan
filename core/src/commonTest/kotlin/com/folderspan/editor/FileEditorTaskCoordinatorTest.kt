package com.folderspan.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileEditorTaskCoordinatorTest {
    @Test
    fun interactiveReadCancelsPrefetch() = runTest {
        val coordinator = FileEditorTaskCoordinator(StandardTestDispatcher(testScheduler))
        val prefetchStarted = CompletableDeferred<Unit>()
        val prefetch = coordinator.launch(FileEditorTaskKind.Prefetch) {
            prefetchStarted.complete(Unit)
            awaitCancellation()
        }
        prefetchStarted.await()

        coordinator.launch(FileEditorTaskKind.InteractiveRead) { }.join()

        assertTrue(prefetch.isCancelled)
        coordinator.close()
    }

    @Test
    fun closeCancelsAllBackgroundKinds() = runTest {
        val coordinator = FileEditorTaskCoordinator(StandardTestDispatcher(testScheduler))
        val jobs = listOf(
            FileEditorTaskKind.Prefetch,
            FileEditorTaskKind.Search,
            FileEditorTaskKind.LineIndex,
            FileEditorTaskKind.Analysis,
        ).map { kind ->
            coordinator.launch(kind) { awaitCancellation() }
        }

        coordinator.close()

        jobs.forEach { job -> assertTrue(job.isCancelled) }
    }

    @Test
    fun resourceIntensiveTasksHaveProcessWideBoundedParallelism() = runTest {
        val coordinators = List(3) {
            FileEditorTaskCoordinator(StandardTestDispatcher(testScheduler))
        }
        val release = CompletableDeferred<Unit>()
        val twoStarted = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0

        val jobs = coordinators.map { coordinator ->
            coordinator.launch(FileEditorTaskKind.Analysis) {
                active += 1
                maxActive = maxOf(maxActive, active)
                if (active == 2) twoStarted.complete(Unit)
                try {
                    release.await()
                } finally {
                    active -= 1
                }
            }
        }

        twoStarted.await()
        assertEquals(2, maxActive)
        release.complete(Unit)
        jobs.joinAll()
        coordinators.forEach(FileEditorTaskCoordinator::close)
    }
}
