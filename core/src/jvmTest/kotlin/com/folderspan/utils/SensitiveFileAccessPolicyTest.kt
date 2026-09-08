package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveFileAccessPolicyTest {
    @Test
    fun classifyMarksSensitiveRootsAndDescendantsWithoutMatchingSimilarPrefixes() {
        val rules = listOf(
            SensitivePathRule(
                path = "/private/database",
                sensitivity = FileSensitivity.Critical,
                category = "database",
            ),
            SensitivePathRule(
                path = "/private/cache",
                sensitivity = FileSensitivity.Sensitive,
                category = "cache",
            ),
        )

        val database = SensitiveFileAccessPolicy.classify("/private/database/main.db", "/", rules)
        val cache = SensitiveFileAccessPolicy.classify("/private/other/../cache/item", "/", rules)
        val ordinary = SensitiveFileAccessPolicy.classify("/private/database-copy/main.db", "/", rules)

        assertEquals(FileSensitivity.Critical, database.sensitivity)
        assertEquals("database", database.category)
        assertEquals(FileSensitivity.Sensitive, cache.sensitivity)
        assertEquals("cache", cache.category)
        assertEquals(FileSensitivity.None, ordinary.sensitivity)
    }

    @Test
    fun classifyFailsClosedForMalformedPaths() {
        val classification = SensitiveFileAccessPolicy.classify(
            path = "relative/file.txt",
            separator = "/",
            rules = emptyList(),
        )

        assertEquals(FileSensitivity.Critical, classification.sensitivity)
        assertEquals("invalid_path", classification.category)
        assertFalse(classification.isValid)
    }

    @Test
    fun classifyUsesCaseInsensitiveWindowsPathComparison() {
        val classification = SensitiveFileAccessPolicy.classify(
            path = "C:\\APP\\PRIVATE\\Token.bin",
            separator = "\\",
            rules = listOf(
                SensitivePathRule(
                    path = "c:\\app\\private",
                    sensitivity = FileSensitivity.Critical,
                    category = "credentials",
                )
            ),
        )

        assertEquals(FileSensitivity.Critical, classification.sensitivity)
        assertEquals("credentials", classification.category)
    }

    @Test
    fun classifyStripsWindowsVerbatimPrefixesBeforeMatching() {
        val rules = listOf(
            SensitivePathRule(
                path = "c:\\app\\private",
                sensitivity = FileSensitivity.Critical,
                category = "credentials",
            ),
            SensitivePathRule(
                path = "\\\\server\\share\\private",
                sensitivity = FileSensitivity.Sensitive,
                category = "shared_credentials",
            ),
        )

        listOf(
            "\\\\?\\C:\\app\\private\\token.bin",
            "\\\\?\\c:\\APP\\PRIVATE\\Token.bin",
            "\\\\.\\C:\\app\\private\\token.bin",
            "\\??\\C:\\app\\private\\token.bin",
            "//?/C:/app/private/token.bin",
        ).forEach { path ->
            val classification = SensitiveFileAccessPolicy.classify(path, "\\", rules)
            assertEquals(FileSensitivity.Critical, classification.sensitivity, "path=$path")
            assertEquals("credentials", classification.category, "path=$path")
        }

        val unc = SensitiveFileAccessPolicy.classify("\\\\?\\UNC\\server\\share\\private\\token.bin", "\\", rules)
        assertEquals(FileSensitivity.Sensitive, unc.sensitivity)
        assertEquals("shared_credentials", unc.category)
    }

    @Test
    fun classifyFoldsCaseWhenPlatformFilesystemIsCaseInsensitive() {
        val rules = listOf(
            SensitivePathRule(
                path = "/Users/webb/Library/Application Support/FolderSpan",
                sensitivity = FileSensitivity.Critical,
                category = "application_private_data",
            ),
        )

        val bypass = SensitiveFileAccessPolicy.classify(
            path = "/users/webb/library/application support/folderspan/folderspan.db",
            separator = "/",
            rules = rules,
            caseInsensitive = true,
        )
        val exact = SensitiveFileAccessPolicy.classify(
            path = "/Users/webb/Library/Application Support/FolderSpan/tls-identity/key.pem",
            separator = "/",
            rules = rules,
            caseInsensitive = true,
        )
        val unrelated = SensitiveFileAccessPolicy.classify(
            path = "/users/webb/documents/folderspan.db",
            separator = "/",
            rules = rules,
            caseInsensitive = true,
        )

        assertEquals(FileSensitivity.Critical, bypass.sensitivity)
        assertEquals("application_private_data", bypass.category)
        assertEquals(FileSensitivity.Critical, exact.sensitivity)
        assertEquals(FileSensitivity.None, unrelated.sensitivity)
    }

    @Test
    fun classifyFoldsDarwinPrivateVarAlias() {
        val libraryRoot = "/var/mobile/Containers/Data/Application/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE/Library"
        val privateLibraryRoot = "/private$libraryRoot"
        val varRule = listOf(
            SensitivePathRule(
                path = libraryRoot,
                sensitivity = FileSensitivity.Critical,
                category = "application_private_data",
            ),
        )
        val privateRule = listOf(
            SensitivePathRule(
                path = privateLibraryRoot,
                sensitivity = FileSensitivity.Critical,
                category = "application_private_data",
            ),
        )

        val exact = SensitiveFileAccessPolicy.classify(
            path = "$libraryRoot/folderspan.db",
            separator = "/",
            rules = varRule,
            caseInsensitive = true,
        )
        val aliased = SensitiveFileAccessPolicy.classify(
            path = "$privateLibraryRoot/folderspan.db",
            separator = "/",
            rules = varRule,
            caseInsensitive = true,
        )
        val reverse = SensitiveFileAccessPolicy.classify(
            path = "$libraryRoot/folderspan.db",
            separator = "/",
            rules = privateRule,
            caseInsensitive = true,
        )
        val unrelated = SensitiveFileAccessPolicy.classify(
            path = "/private/varish/mobile/Library/folderspan.db",
            separator = "/",
            rules = varRule,
            caseInsensitive = true,
        )

        assertEquals(FileSensitivity.Critical, exact.sensitivity)
        assertEquals("application_private_data", exact.category)
        assertEquals(FileSensitivity.Critical, aliased.sensitivity)
        assertEquals("application_private_data", aliased.category)
        assertEquals(FileSensitivity.Critical, reverse.sensitivity)
        assertEquals(FileSensitivity.None, unrelated.sensitivity)
    }

    @Test
    fun deniedExceptionRechecksCanonicalPathAfterLexicalMiss() {
        val cacheRoot = java.io.File(PathUtils.getCachePath(), "file-editor-backups")
        val protectedFile = cacheRoot.resolve("secret.txt")
        val aliasDir = java.nio.file.Files.createTempDirectory("sensitive-canonical-alias").toFile()
        val alias = aliasDir.resolve("alias.txt").toPath()
        try {
            cacheRoot.mkdirs()
            protectedFile.writeText("secret")
            java.nio.file.Files.createSymbolicLink(alias, protectedFile.toPath())

            assertEquals(FileSensitivity.None, SensitiveFileAccessPolicy.classify(alias.toString()).sensitivity)
            assertNotNull(SensitiveFileAccessPolicy.deniedException(alias.toString()))
            assertNotNull(SensitiveFileAccessPolicy.deniedException(protectedFile.path))
        } finally {
            java.nio.file.Files.deleteIfExists(alias)
            aliasDir.deleteRecursively()
            protectedFile.delete()
        }
    }

    @Test
    fun desktopApplicationDataIsProtectedButHomeRootIsNotBlanketBlocked() {
        val applicationData = SensitiveFileAccessPolicy.classify(
            resolveDesktopApplicationDataDirectory().resolve("folderspan.db").toString()
        )
        val home = SensitiveFileAccessPolicy.classify(PathUtils.getHomePath())

        assertTrue(applicationData.isProtected)
        assertEquals("application_private_data", applicationData.category)
        assertEquals(FileSensitivity.None, home.sensitivity)
    }

    @Test
    fun fileProviderExceptionRejectsDatabaseAndTlsIdentityButAllowsOrdinaryFiles() {
        val separator = PathUtils.getPathSeparator()
        val appPath = PathUtils.getAppPath().trimEnd('/', '\\')
        val databasePath = appPath + separator + "folderspan.db"
        val tlsIdentityPath = appPath + separator + "tls-identity" + separator + "identity.fmi"
        val ordinary = java.nio.file.Files.createTempFile("file-provider-ordinary", ".txt").toString()

        assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(databasePath))
        assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(tlsIdentityPath))
        assertNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(ordinary))
    }

    @Test
    fun fileProviderExceptionAllowsApplicationCacheCategory() {
        val cacheClassification = SensitivePathClassification(
            sensitivity = FileSensitivity.Sensitive,
            category = "application_cache",
        )
        val databaseClassification = SensitivePathClassification(
            sensitivity = FileSensitivity.Critical,
            category = "database",
        )

        assertTrue(cacheClassification.isProtected)
        assertNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(cacheClassification))
        assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(databaseClassification))
    }

    @Test
    fun fileProviderExceptionRejectsProHttpCacheEvenWhenNestedUnderApplicationData() {
        val cachePath = resolveDesktopApplicationDataDirectory()
            .resolve("pro_http_cache")
            .resolve("entry.bin")
            .toString()
        val classification = SensitiveFileAccessPolicy.classify(cachePath)

        assertEquals(FileSensitivity.Sensitive, classification.sensitivity)
        assertEquals("pro_http_cache", classification.category)
        assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(classification))
        assertNotNull(SensitiveFileAccessPolicy.deniedExceptionForFileProvider(cachePath))
    }

    @Test
    fun androidFileProviderPathsDoNotExposeRootOrFilesDir() {
        val source = locateRepoFile("app/androidApp/src/main/res/xml/provider_paths.xml").readText()

        assertFalse(source.contains("<root-path"), source)
        assertFalse(source.contains("<files-path"), source)
        assertTrue(source.contains("<cache-path"), source)
        assertTrue(source.contains("<external-path"), source)
    }
}

private fun locateRepoFile(relativePath: String): java.io.File {
    var directory = java.io.File(System.getProperty("user.dir")).absoluteFile
    repeat(8) {
        val candidate = java.io.File(directory, relativePath)
        if (candidate.isFile) return candidate
        directory = directory.parentFile ?: return@repeat
    }
    error("missing $relativePath from ${System.getProperty("user.dir")}")
}
