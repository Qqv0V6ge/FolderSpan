package com.folderspan.settings

import com.folderspan.utils.LogKit
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSLock
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

private const val KEYCHAIN_SERVICE = "com.folderspan.secure-settings"

@OptIn(ExperimentalForeignApi::class)
internal object IosKeychainStore : StringSecretVault {
    private val fallbackLock = NSLock()
    private val fallbackValues = mutableMapOf<String, String>()
    private var usesFallback = false

    override fun get(key: String): String? {
        if (usesFallback()) return fallbackValue(key)
        return withKeychainQuery(key) { query ->
            query.setValue(kSecReturnData, kCFBooleanTrue)
            query.setValue(kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped null
                if (enableFallbackFor(status)) return@memScoped fallbackValue(key)
                require(status == errSecSuccess) { "Keychain read failed: $status" }
                val data = result.value ?: return@memScoped null
                try {
                    data.toByteArray().decodeToString()
                } finally {
                    CFRelease(data)
                }
            }
        }
    }

    override fun put(key: String, value: String) {
        if (usesFallback()) {
            putFallbackValue(key, value)
            return
        }
        val deleteStatus = withKeychainQuery(key) { SecItemDelete(it) }
        if (enableFallbackFor(deleteStatus)) {
            putFallbackValue(key, value)
            return
        }
        val payload = value.encodeToByteArray().toCFData()
        try {
            val status = withKeychainQuery(key) { attributes ->
                attributes.setValue(kSecValueData, payload)
                attributes.setValue(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(attributes, null)
            }
            if (enableFallbackFor(status)) {
                putFallbackValue(key, value)
                return
            }
            require(status == errSecSuccess) { "Keychain write failed: $status" }
            removeFallbackValue(key)
        } finally {
            CFRelease(payload)
        }
    }

    override fun remove(key: String) {
        if (usesFallback()) {
            removeFallbackValue(key)
            return
        }
        val status = withKeychainQuery(key) { SecItemDelete(it) }
        if (enableFallbackFor(status)) {
            removeFallbackValue(key)
            return
        }
        require(status == errSecSuccess || status == errSecItemNotFound) {
            "Keychain delete failed: $status"
        }
    }

    override fun contains(key: String): Boolean = get(key) != null

    override fun keys(): Set<String> {
        if (usesFallback()) return fallbackKeys()
        return withKeychainQuery { query ->
            query.setValue(kSecReturnAttributes, kCFBooleanTrue)
            query.setValue(kSecMatchLimit, kSecMatchLimitAll)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped emptySet()
                if (enableFallbackFor(status)) return@memScoped fallbackKeys()
                if (status != errSecSuccess) return@memScoped emptySet()
                val resultValue = result.value ?: return@memScoped emptySet()
                try {
                    val items: CFArrayRef = resultValue.reinterpret()
                    buildSet {
                        repeat(CFArrayGetCount(items).toInt()) { index ->
                            val item = CFArrayGetValueAtIndex(items, index.toLong())
                                ?.let { raw -> interpretCPointer<cnames.structs.__CFDictionary>(raw.rawValue) }
                                ?: return@repeat
                            val account = CFDictionaryGetValue(item, kSecAttrAccount)
                                ?.toKotlinString()
                            if (!account.isNullOrEmpty()) add(account)
                        }
                    }
                } finally {
                    CFRelease(resultValue)
                }
            }
        }
    }

    override fun clear() {
        if (usesFallback()) {
            clearFallbackValues()
            return
        }
        val status = withKeychainQuery { SecItemDelete(it) }
        if (enableFallbackFor(status)) {
            clearFallbackValues()
            return
        }
        require(status == errSecSuccess || status == errSecItemNotFound) {
            "Keychain clear failed: $status"
        }
        clearFallbackValues()
    }

    private fun enableFallbackFor(status: Int): Boolean {
        if (status != errSecMissingEntitlement && status != errSecNotAvailable) return false
        val enabledNow = fallbackLock.withLock {
            if (usesFallback) {
                false
            } else {
                usesFallback = true
                true
            }
        }
        if (enabledNow) {
            LogKit.w("iOS Keychain unavailable (status=$status); using process-memory secure settings")
        }
        return true
    }

    private fun usesFallback(): Boolean = fallbackLock.withLock { usesFallback }

    private fun fallbackValue(key: String): String? = fallbackLock.withLock { fallbackValues[key] }

    private fun fallbackKeys(): Set<String> = fallbackLock.withLock { fallbackValues.keys.toSet() }

    private fun putFallbackValue(key: String, value: String) {
        fallbackLock.withLock { fallbackValues[key] = value }
    }

    private fun removeFallbackValue(key: String) {
        fallbackLock.withLock { fallbackValues.remove(key) }
    }

    private fun clearFallbackValues() {
        fallbackLock.withLock { fallbackValues.clear() }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun createKeychainQuery(account: String? = null): CFMutableDictionaryRef {
    val query = requireNotNull(
        CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        ),
    ) { "Unable to create Keychain query" }
    query.setValue(kSecClass, kSecClassGenericPassword)
    query.setString(kSecAttrService, KEYCHAIN_SERVICE)
    if (account != null) query.setString(kSecAttrAccount, account)
    return query
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withKeychainQuery(
    account: String? = null,
    block: (CFMutableDictionaryRef) -> T,
): T {
    val query = createKeychainQuery(account)
    return try {
        block(query)
    } finally {
        CFRelease(query)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFMutableDictionaryRef.setValue(key: COpaquePointer?, value: COpaquePointer?) {
    CFDictionarySetValue(this, key, value)
}

@OptIn(ExperimentalForeignApi::class)
private fun CFMutableDictionaryRef.setString(key: COpaquePointer?, value: String) {
    val string = requireNotNull(CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)) {
        "Unable to create Keychain string"
    }
    try {
        setValue(key, string)
    } finally {
        CFRelease(string)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef {
    if (isEmpty()) return requireNotNull(CFDataCreate(null, null, 0))
    return usePinned { pinned ->
        requireNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong()))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.toKotlinString(): String = interpretObjCPointer(rawValue)

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.toByteArray(): ByteArray {
    val data: CFDataRef = reinterpret()
    val size = CFDataGetLength(data).toInt()
    if (size == 0) return ByteArray(0)
    val source = CFDataGetBytePtr(data) ?: return ByteArray(0)
    return ByteArray(size).also { destination ->
        destination.usePinned { pinned ->
            memcpy(pinned.addressOf(0), source, size.toULong())
        }
    }
}
