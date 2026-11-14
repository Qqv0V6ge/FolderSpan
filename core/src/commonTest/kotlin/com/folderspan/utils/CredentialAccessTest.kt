package com.folderspan.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CredentialAccessTest {
    @Test
    fun cancellationReleasesAccessForTheNextOperation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val operation = launch {
            DataEncryptionKey.withCredentialAccess {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        operation.cancelAndJoin()
        assertEquals(42, withTimeout(1000) { DataEncryptionKey.withCredentialAccess { 42 } })
    }

    @Test
    fun failuresAndDispatcherChangesDoNotKeepAccessLocked() = runTest {
        assertFailsWith<IllegalStateException> {
            DataEncryptionKey.withCredentialAccess {
                withContext(Dispatchers.Default) { error("fixture failure") }
            }
        }
        assertEquals(42, withContext(Dispatchers.Default) {
            withTimeout(1000) {
                DataEncryptionKey.withCredentialAccess { withContext(Dispatchers.Default) { 42 } }
            }
        })
    }
}
