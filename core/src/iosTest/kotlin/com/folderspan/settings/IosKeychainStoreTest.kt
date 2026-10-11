package com.folderspan.settings

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.CoreFoundation.CFDictionaryGetCount
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetTypeID
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass

class IosKeychainStoreTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun keychainQueryContainsNativeCoreFoundationValues() {
        val query = createKeychainQuery("test-account")
        try {
            assertEquals(3, CFDictionaryGetCount(query))
            listOf(kSecClass, kSecAttrService, kSecAttrAccount).forEach { key ->
                val value = assertNotNull(CFDictionaryGetValue(query, key))
                assertEquals(CFStringGetTypeID(), CFGetTypeID(value))
            }
        } finally {
            CFRelease(query)
        }
    }

    @Test
    fun secretCanBeReadWrittenListedAndRemoved() {
        val key = "folderspan.test.${Random.nextLong()}"

        try {
            assertNull(IosKeychainStore.get(key))

            IosKeychainStore.put(key, "test-secret")

            assertEquals("test-secret", IosKeychainStore.get(key))
            assertTrue(IosKeychainStore.contains(key))
            assertTrue(key in IosKeychainStore.keys())

            IosKeychainStore.remove(key)
            assertFalse(IosKeychainStore.contains(key))
        } finally {
            IosKeychainStore.remove(key)
        }
    }
}
