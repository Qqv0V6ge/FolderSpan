package com.folderspan.privileged

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegedFileReadinessTest {
    @Test
    fun preferredAuthorizedBackendWaitsForReadinessEvent() = runTest {
        val readiness = CompletableDeferred<Unit>()
        val backend = object : PrivilegedFileBackend {
            override fun isAuthorized(): Boolean = true

            override suspend fun awaitReady(): Boolean {
                readiness.await()
                return true
            }

            override fun <T> withClient(
                operation: (PrivilegedFileClient) -> Result<T>
            ): Result<T> = Result.failure(UnsupportedOperationException())
        }
        var result: Boolean? = null

        val job = launch {
            result = PrivilegedFileAccess.awaitPreferredBackendReady(listOf(backend))
        }
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(null, result)

        readiness.complete(Unit)
        job.join()

        assertEquals(true, result)
    }
}
