package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageReference
import com.folderspan.ignore.IgnoreMatcher
import com.folderspan.ignore.IgnorePreferenceScope
import com.folderspan.ignore.ResolvedIgnoreMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSourceScopeTest {
    private fun file(path: String, directory: Boolean = false) = FileSimpleInfo.pathFileSimpleInfo(path)
        .withCopy(name = path.substringAfterLast('/'), isDirectory = directory)
    private fun conflict(path: String, origin: String = "a.jpg") =
        SyncPlannedOperation(SyncOperationKind.ConflictCopy, "source", path, "target", sourceRelativePath = origin)

    @Test fun eachDirectoryAliasUsesOnlyItsOwnPhysicalIgnoreRules() {
        val photos = file("/one/photos", true)
        val docs = file("/two/docs", true)
        val scope = SyncSourceScope(photos, listOf(photos, docs, file("/three/current.txt")), "/")
        fun matcher(root: FileSimpleInfo, line: String) = ResolvedIgnoreMatcher(
            IgnorePreferenceScope(FileProtocol.Local, "", root.path, listOf(".gitignore"), "/"),
            IgnoreMatcher(IgnoreMatcher.parse(listOf(line))))
        val matchers = mapOf("photos" to matcher(photos, "private.jpg"), "docs" to matcher(docs, "draft.txt"))
        assertTrue(scope.ignored("photos/private.jpg", false, matchers))
        assertTrue(scope.ignored("docs/draft.txt", false, matchers))
        assertFalse(scope.ignored("photos/draft.txt", false, matchers))
        assertFalse(scope.ignored("docs/private.jpg", false, matchers))
        assertFalse(scope.ignored("current.txt", false, matchers))
        assertTrue(scope.ignored("photos/.gitignore", false, matchers))
        assertTrue(scope.ignored("photos/.folderspan-recycle/old", false, matchers))
        assertTrue(scope.ignored("unselected.txt", false, matchers))
    }

    @Test fun selectedFilesAcceptOnlyTheirOwnPathAndExplicitConflictSiblings() {
        val selected = file("/photos/a.jpg")
        val scope = SyncSourceScope(selected, listOf(selected), "/")
        assertTrue(scope.contains("a.jpg"))
        assertFalse(scope.contains("unrelated.txt"))
        assertFalse(scope.contains("a.jpg/new"))
        assertEquals("/photos/a.jpg", scope.location("a.jpg").path)
        val planned = conflict("a (conflict target run).jpg")
        assertEquals("/photos/a (conflict target run).jpg", scope.location(planned.relativePath, planned).path)
        assertEquals("/photos", scope.artifactRoot("a.jpg").path)
        assertEquals("/photos", scope.artifactRoot(planned.relativePath, planned).path)
        for (path in listOf("unrelated.txt", "../escape", "/outside", "a.jpg/new", "a\\new", "nested/conflict")) {
            assertFailsWith<IllegalArgumentException>(path) { scope.location(path, planned) }
        }
        assertFailsWith<IllegalArgumentException> { scope.location("conflict.jpg", conflict("conflict.jpg", "another.jpg")) }
        assertFailsWith<IllegalArgumentException> { scope.location(planned.relativePath, planned.copy(kind = SyncOperationKind.Copy)) }
        val multiple = SyncSourceScope(selected, listOf(selected, file("/other/b.jpg")), "/")
        assertFailsWith<IllegalArgumentException> { multiple.location("b.jpg", conflict("b.jpg")) }
    }

    @Test fun directoryAliasesMapOnlyTheirOwnSubtreeAndKeepRecoveryInsideTheSelectedRoot() {
        val first = file("/one/photos", true)
        val second = file("/two/docs", true)
        val scope = SyncSourceScope(first, listOf(first, second), "/")
        assertEquals("/one/photos", scope.location("photos").path)
        assertEquals("/one/photos/2026/picture.jpg", scope.location("photos/2026/picture.jpg").path)
        assertEquals("/two/docs/readme.txt", scope.location("docs/readme.txt").path)
        assertEquals("/two/docs", scope.artifactRoot("docs/readme.txt").path)
        assertFalse(scope.contains("photo-other/new"))
        assertFalse(scope.contains("docs/../private"))
        assertFailsWith<IllegalArgumentException> { scope.location("photos (conflict)", conflict("photos (conflict)", "photos")) }
        assertFailsWith<IllegalArgumentException> { scope.artifactRoot("unrelated") }
    }

    @Test fun stableReferencesRemainBoundToTheSelectedDirectoryOrFilesParent() {
        val selected = file("/photos/a.jpg").withCopy(protocol = FileProtocol.Network, protocolId = "drive",
            storageReference = StorageReference(id = "file-id", parentId = "folder-id"))
        val scope = SyncSourceScope(selected, listOf(selected), "/")
        assertEquals(StorageReference(id = "folder-id"), scope.artifactRoot("a.jpg").storageReference)
        assertEquals(StorageReference(ancestorId = "folder-id", ancestorPath = "/photos"), scope.location("conflict.jpg", conflict("conflict.jpg")).storageReference)
        val directory = selected.withCopy(path = "/photos", name = "photos", isDirectory = true)
        val nested = SyncSourceScope(directory, listOf(directory), "/").location("photos/nested/a.jpg")
        assertEquals(StorageReference(ancestorId = "folder-id", ancestorPath = "/photos"), nested.storageReference)
    }

    @Test fun ordinaryRootsAndWindowsSeparatorsUseTheSameRelativeScopeChecks() {
        val scope = SyncSourceScope(file("/root", true), emptyList(), "/")
        assertEquals("/root/a/b", scope.location("a/b").path)
        assertEquals("/root", scope.artifactRoot("a/b").path)
        assertFailsWith<IllegalArgumentException> { scope.location("a/../../outside") }
        val windows = SyncSourceScope(file("C:\\photos", true), emptyList(), "\\")
        assertEquals("C:\\photos\\2026\\a.jpg", windows.location("2026/a.jpg").path)
        val first = file("/one/photos", true)
        assertFailsWith<IllegalArgumentException> { SyncSourceScope(first, listOf(first, file("/two/photos", true)), "/") }
        assertFailsWith<IllegalArgumentException> { SyncSourceScope(first, listOf(first, file("/one/photos/nested", true)), "/") }
    }
}
