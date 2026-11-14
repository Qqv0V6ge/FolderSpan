package com.folderspan.utils

import com.folderspan.test.createInMemorySettings
import com.russhwolf.settings.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataEncryptionKeyConcurrencyTest {
    @AfterTest
    fun cleanup() = DataEncryptionKey.clearForTests()

    @Test
    fun concurrentFirstEncryptionUsesOnePersistedKey() {
        val settings = PausedFirstKeyReadSettings()
        DataEncryptionKey.init(settings)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<String> { SymmetricCrypto.encrypt("fixture-first") }
            assertTrue(settings.entered.await(5, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second = workers.submit<String> {
                secondStarted.countDown()
                SymmetricCrypto.encrypt("fixture-second")
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
            settings.resume.countDown()

            assertEquals("fixture-first", SymmetricCrypto.decrypt(first.get(5, TimeUnit.SECONDS)))
            assertEquals("fixture-second", SymmetricCrypto.decrypt(second.get(5, TimeUnit.SECONDS)))
            assertEquals(1, settings.keyWrites)
        } finally {
            settings.resume.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun inFlightInitializationCannotOverwriteAnImportedKey() {
        val remoteKey = DataEncryptionKey.encode(ByteArray(32) { 7 })
        val settings = PausedFirstKeyReadSettings()
        DataEncryptionKey.init(settings)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<ByteArray> { DataEncryptionKey.keyBytes() }
            assertTrue(settings.entered.await(5, TimeUnit.SECONDS))
            val replacementStarted = CountDownLatch(1)
            val replacement = workers.submit<Boolean> {
                replacementStarted.countDown()
                DataEncryptionKey.replaceFromRemote(remoteKey)
            }
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { replacement.get(100, TimeUnit.MILLISECONDS) }
            settings.resume.countDown()
            first.get(5, TimeUnit.SECONDS)

            assertTrue(replacement.get(5, TimeUnit.SECONDS))
            assertEquals(remoteKey, DataEncryptionKey.encodedForSync())
        } finally {
            settings.resume.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun storageFailureReleasesTheKeyLock() {
        val store = createInMemorySettings()
        var fail = true
        DataEncryptionKey.init(object : Settings by store {
            override fun putString(key: String, value: String) {
                if (key == SettingsUtils.KEY_CRYPTO_KEY && fail) {
                    fail = false
                    error("fixture storage failure")
                }
                store.putString(key, value)
            }
        })
        assertFailsWith<IllegalStateException> { DataEncryptionKey.keyBytes() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(32, worker.submit<ByteArray> { DataEncryptionKey.keyBytes() }.get(5, TimeUnit.SECONDS).size)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

private class PausedFirstKeyReadSettings(private val store: Settings = createInMemorySettings()) : Settings by store {
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    var keyWrites = 0
        private set
    private var pause = true

    override fun getStringOrNull(key: String): String? {
        val value = synchronized(store) { store.getStringOrNull(key) }
        val shouldPause = synchronized(store) {
            if (key == SettingsUtils.KEY_CRYPTO_KEY && pause) {
                pause = false
                true
            } else false
        }
        if (shouldPause) {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        return value
    }

    override fun putString(key: String, value: String) = synchronized(store) {
        if (key == SettingsUtils.KEY_CRYPTO_KEY) keyWrites++
        store.putString(key, value)
    }
}
