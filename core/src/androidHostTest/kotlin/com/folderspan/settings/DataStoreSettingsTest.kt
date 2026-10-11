package com.folderspan.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.folderspan.utils.SettingsUtils
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataStoreSettingsTest {
    @Test
    fun repeatedReadsUseTheStartupSnapshotInsteadOfCollectingDataStoreAgain() {
        val dataStore = CountingDataStore()
        val settings = DataStoreSettings(dataStore, PassthroughAead)

        assertEquals("first", settings.getString("missing", "first"))
        assertEquals("second", settings.getString("missing", "second"))
        assertEquals(false, settings.hasKey("missing"))

        assertEquals(1, dataStore.collectionCount)
    }

    @Test
    fun assignedDeviceIdIsWriteOnce() {
        val settings = DataStoreSettings(CountingDataStore(), PassthroughAead)

        settings.putString(SettingsUtils.KEY_DEVICE_ID, "  ")
        assertNull(settings.getStringOrNull(SettingsUtils.KEY_DEVICE_ID))
        assertFalse(settings.hasKey(SettingsUtils.KEY_DEVICE_ID))

        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-a")
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-b")
        settings.putString(SettingsUtils.KEY_DEVICE_ID, "device-a")
        settings.remove(SettingsUtils.KEY_DEVICE_ID)
        settings.clear()

        assertEquals("device-a", settings.getString(SettingsUtils.KEY_DEVICE_ID, ""))
        assertTrue(settings.hasKey(SettingsUtils.KEY_DEVICE_ID))
    }
}

private class CountingDataStore : DataStore<Preferences> {
    private var preferences: Preferences = emptyPreferences()
    var collectionCount: Int = 0
        private set

    override val data: Flow<Preferences>
        get() = flow {
            collectionCount += 1
            emit(preferences)
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        preferences = transform(preferences)
        return preferences
    }
}

private object PassthroughAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = plaintext

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray = ciphertext
}
