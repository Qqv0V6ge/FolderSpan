package com.folderspan.shizuku

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShizukuFileServiceHostTest {
    @Test
    fun recursiveDeleteRemovesLinkWithoutDeletingItsTarget() {
        val parent = Files.createTempDirectory("folderspan-shizuku-delete-").toFile()
        val target = parent.resolve("outside").apply { mkdirs() }
        val secret = target.resolve("secret.txt").apply { writeText("secret") }
        val root = parent.resolve("root").apply { mkdirs() }
        val link = root.toPath().resolve("escape")
        try {
            Files.createSymbolicLink(link, target.toPath())

            assertTrue(deleteFileTreeNoFollow(root))
            assertFalse(root.exists())
            assertTrue(secret.exists())
            assertEquals("secret", secret.readText())
        } finally {
            Files.deleteIfExists(link)
            parent.deleteRecursively()
        }
    }
}
