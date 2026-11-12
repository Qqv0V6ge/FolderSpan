package com.folderspan.service.network

import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class StorageContentFileProtectionJvmTest {
    @Test
    fun fileIdentitySurvivesRenameButDoesNotMatchAReplacement() {
        val directory = Files.createTempDirectory("content-file-identity")
        val original = directory.resolve("original.part")
        val renamed = directory.resolve("renamed.part")
        try {
            Files.writeString(original, "same bytes")
            val identity = assertNotNull(StorageContentFileProtection.fileIdentity(original.toString().toPath()))
            Files.move(original, renamed)
            assertEquals(identity, StorageContentFileProtection.fileIdentity(renamed.toString().toPath()))

            Files.writeString(original, "same bytes")
            Files.setLastModifiedTime(original, Files.getLastModifiedTime(renamed))
            val replacement = assertNotNull(StorageContentFileProtection.fileIdentity(original.toString().toPath()))
            assertNotEquals(identity, replacement)
        } finally {
            Files.deleteIfExists(original)
            Files.deleteIfExists(renamed)
            Files.deleteIfExists(directory)
        }
    }
}
