package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Active only in the separate Mac App Store distribution. */
object MacAppStoreAccess {
    val isEnabled: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("folderspan.macAppStore") == "true"

    private interface NativeAccess : Library {
        fun FSContainerPaths(): Pointer?
        fun FSSelectDirectory(): Pointer?
        fun FSResolveDirectory(bookmark: String): Pointer?
        fun FSReleaseDirectories()
        fun FSFreeString(value: Pointer)
    }

    private val native: NativeAccess by lazy {
        val resources = requireNotNull(System.getProperty("compose.application.resources.dir"))
        Native.load(Path.of(resources, "native", "libfolderspan-macos-access.dylib").toString(),
            NativeAccess::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }
    private val bridge = object : MacDirectoryAccessBridge {
        override fun selectDirectory(): AuthorizedMacDirectory? {
            val result = consume(native.FSSelectDirectory())
            if (result["cancelled"]?.jsonPrimitive?.booleanOrNull == true) return null
            return result.directory()
        }

        override fun resolveDirectory(bookmark: String): AuthorizedMacDirectory =
            consume(native.FSResolveDirectory(bookmark)).directory()
    }
    private val store by lazy {
        MacDirectoryAccessStore(resolveDesktopApplicationDataDirectory().resolve("mac-folder-access.json"), bridge)
    }

    /** Must run before any settings, logging, locks, or application-data lookup. */
    fun initializeContainer() {
        if (!isEnabled) return
        val paths = consume(native.FSContainerPaths())
        System.setProperty("user.home", paths.required("home"))
        System.setProperty("java.io.tmpdir", paths.required("temporary"))
        Runtime.getRuntime().addShutdownHook(Thread({ native.FSReleaseDirectories() }, "mac-directory-scopes"))
    }

    /** Pending application cleanup is completed before restoring any saved scopes. */
    fun restoreDirectories(): Result<Unit> = runCatching { if (isEnabled) store.restore() }

    fun accessibleDirectories(): List<String> =
        if (isEnabled) runCatching { store.accessibleDirectories() }.getOrDefault(emptyList()) else emptyList()

    suspend fun selectDirectory(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            check(isEnabled)
            store.selectDirectory()
        }
    }

    private fun consume(pointer: Pointer?): JsonObject {
        checkNotNull(pointer) { "Folder authorization is unavailable" }
        return try {
            Json.parseToJsonElement(pointer.getString(0, "UTF-8")).jsonObject
        } finally {
            native.FSFreeString(pointer)
        }
    }

    private fun JsonObject.required(key: String): String =
        requireNotNull(this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })

    private fun JsonObject.directory(): AuthorizedMacDirectory {
        check(this["error"] == null) { "Unable to authorize this folder. Please select it again." }
        return AuthorizedMacDirectory(required("path"), required("bookmark"))
    }
}
