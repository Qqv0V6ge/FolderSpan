package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.service.network.StorageReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CopyDestinationStateTest {
    private val local = endpoint(FileProtocol.Local, "")
    private val network = endpoint(FileProtocol.Network, "network-one")

    @Test
    fun snapshotsRetainEverySourceAndCannotBeMutatedThroughFilesOrInputLists() {
        val file = file("/source.txt", local, directory = false)
        val remote = file("/source.txt", network, directory = false)
            .copy(storageReference = StorageReference(id = "object-one"), size = 12, updatedDate = 34)
        val inputs = mutableListOf(CopyFileSnapshot(local, file), CopyFileSnapshot(network, remote))
        val draft = CopyDestinationState("runtime", inputs)
        inputs.clear()
        file.path = "/changed.txt"
        remote.protocolId = "other"
        draft.sources[1].file.path = "/returned-copy.txt"
        assertEquals(2, draft.sources.size)
        assertEquals("/source.txt", draft.sources[0].file.path)
        assertEquals("network-one", draft.sources[1].file.protocolId)
        assertEquals(StorageReference(id = "object-one"), draft.sources[1].location.storageReference)
        assertEquals(12L, draft.sources[1].file.size)
        assertEquals(34L, draft.sources[1].file.updatedDate)
    }

    @Test
    fun objectAndCanonicalPathAliasesCountAsOneTarget() {
        val draft = draft()
        val first = destination("/one", reference = StorageReference(id = "same"))
        val alias = destination("/alias", reference = StorageReference(id = "same"))
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(first))
        assertEquals(CopyDestinationSelectionResult.AlreadySelected, draft.select(alias))
        val canonicalAlias = CopyDestination(
            CopyFileSnapshot(network, file("/link", network), canonicalPath = "/one"), "Alias", "Connection",
        )
        assertEquals(CopyDestinationSelectionResult.AlreadySelected, draft.select(canonicalAlias))
        assertEquals(1, draft.selected.value.size)
    }

    @Test
    fun separateDiscoveryBindingsDeduplicateAliasesButKeepDifferentRootNamespaces() {
        val draft = draft()
        val first = destination("/one")
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(first))
        val alias = CopyDestination(CopyFileSnapshot(network.copy(connectionBinding = "another-window"), file("/one", network)), "Alias", "Alias")
        assertEquals(CopyDestinationSelectionResult.AlreadySelected, draft.select(alias))
        val otherRoot = network.copy(connectionBinding = "another-root", pathNamespace = "root-two")
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(CopyDestination(CopyFileSnapshot(otherRoot, file("/one", otherRoot)), "Other root", "Cloud")))
        assertEquals(2, draft.selected.value.size)
    }

    @Test
    fun sameConnectionAllowsIndependentDirectoriesButRejectsBothNestingDirections() {
        val draft = draft()
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(destination("/one")))
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(destination("/two")))
        assertEquals(CopyDestinationSelectionResult.OverlappingDirectory, draft.select(destination("/one/child")))
        assertEquals(CopyDestinationSelectionResult.OverlappingDirectory, draft.select(destination("/")))
        assertEquals(2, draft.selected.value.size)
    }

    @Test
    fun objectParentReferencesAlsoRejectNestedAliases() {
        val draft = draft()
        draft.select(destination("/one", reference = StorageReference(id = "parent")))
        val child = destination("/alias", reference = StorageReference(id = "child", parentId = "parent"))
        assertEquals(CopyDestinationSelectionResult.OverlappingDirectory, draft.select(child))
    }

    @Test
    fun identityRetainsRuntimeConnectionAndEndpointPathRules() {
        val windows = endpoint(FileProtocol.Device, "windows", separator = "\\")
        val draft = draft()
        val first = destination("C:\\Target", windows)
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(first))
        assertEquals(CopyDestinationSelectionResult.AlreadySelected, draft.select(destination("c:/target/", windows)))
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(destination("/Target")))
        assertEquals(CopyDestinationSelectionResult.Added, draft.select(destination("/target")))
        assertEquals(CopyDestinationSelectionResult.WrongRuntime,
            draft.select(destination("/other", network.copy(runtimeId = "another-runtime"))))
        assertFalse(draft.belongsToRuntime("another-runtime"))
        assertNotEquals(network, network.copy(connectionBinding = "rebound"))
        assertEquals(3, draft.selected.value.size)
    }

    @Test
    fun sourceVersionAndConnectionChangesNeverMutateTheSnapshot() {
        val original = file("/source.txt", network, directory = false).copy(size = 10, updatedDate = 42)
        val snapshot = CopyFileSnapshot(network, original)
        assertTrue(snapshot.matchesCurrent(network, original))
        assertFalse(snapshot.matchesCurrent(network, original.copy(updatedDate = 43)))
        assertFalse(snapshot.matchesCurrent(network, original.copy(size = 11)))
        assertFalse(snapshot.matchesCurrent(network.copy(connectionBinding = "new-binding"), original))
        assertFalse(snapshot.matchesCurrent(network.copy(runtimeId = "new-runtime"), original))
    }

    @Test
    fun sourceFoldersAndFilesCannotBeCopiedIntoThemselves() {
        val folder = CopyFileSnapshot(network, file("/source", network))
        val draft = CopyDestinationState("runtime", listOf(folder))
        assertEquals(CopyDestinationSelectionResult.SourceDestinationConflict, draft.select(destination("/source")))
        assertEquals(CopyDestinationSelectionResult.SourceDestinationConflict, draft.select(destination("/source/child")))
        assertEquals(CopyDestinationSelectionResult.SourceDestinationConflict, draft.select(destination("/")))
        val fileDraft = CopyDestinationState("runtime", listOf(
            CopyFileSnapshot(network, file("/source/file.txt", network, directory = false)),
        ))
        assertEquals(CopyDestinationSelectionResult.SourceDestinationConflict, fileDraft.select(destination("/source")))
        assertEquals(CopyDestinationSelectionResult.Added, fileDraft.select(destination("/other")))
    }

    @Test
    fun routesOwnDifferentRequestsAndSelections() {
        val first = draft()
        val second = draft()
        first.select(destination("/one"))
        second.select(destination("/two"))
        assertNotEquals(first.requestId, second.requestId)
        first.remove(first.selected.value.single().directory)
        assertTrue(first.selected.value.isEmpty())
        assertEquals("/two", second.selected.value.single().directory.file.path)
    }

    @Test
    fun unavailableSelectionIsRetainedAndCanBeRemovedWithoutRetargeting() {
        val draft = draft()
        val target = destination("/one")
        draft.select(target)
        draft.updateAvailability(target.directory, "Disconnected")
        assertEquals("Disconnected", draft.selected.value.single().unavailableReason)
        assertEquals("/one", draft.selected.value.single().directory.file.path)
        draft.remove(target.directory)
        assertTrue(draft.selected.value.isEmpty())
        assertEquals(CopyDestinationSelectionResult.Unavailable,
            draft.select(target.copy(unavailableReason = "Read only")))
        assertEquals(CopyDestinationSelectionResult.Unavailable,
            draft.select(CopyDestination(CopyFileSnapshot(network, file("/file", network, false)), "File", "Connection")))
    }

    @Test
    fun invalidEndpointOrSourceOwnershipIsRejected() {
        assertFailsWith<IllegalArgumentException> { network.copy(connectionBinding = "") }
        assertFailsWith<IllegalArgumentException> { CopyFileSnapshot(network, file("/one", local)) }
        assertFailsWith<IllegalArgumentException> { CopyDestinationState("other", draft().sources) }
        assertFailsWith<IllegalArgumentException> { CopyDestinationState("runtime", emptyList()) }
    }

    @Test
    fun resourceListExcludesShareDestinationsAndKeepsAdditionalDirectories() {
        val draft = draft()
        val targets = listOf(destination("/home", local), destination("/archive", network),
            destination("/extra", network), destination("/shared", endpoint(FileProtocol.Share, "share")))
        assertEquals(3, draft.visibleDestinations(targets).size)
        assertEquals(listOf(FileProtocol.Local, FileProtocol.Network, FileProtocol.Network),
            draft.visibleDestinations(targets).map { it.directory.endpoint.endpoint.protocol })
    }

    @Test
    fun returningToTheResourceListPreservesSelectionAndScrollWithoutSharingOtherRequests() {
        val first = draft()
        val second = draft()
        first.select(destination("/backup"))
        first.rememberScroll(4, 32)
        assertEquals(4, first.controls.value.firstVisibleItemIndex)
        assertEquals(32, first.controls.value.firstVisibleItemOffset)
        assertEquals(1, first.selected.value.size)
        assertEquals(CopyDestinationControls(), second.controls.value)
        assertTrue(second.selected.value.isEmpty())
    }

    private fun draft() = CopyDestinationState("runtime", listOf(
        CopyFileSnapshot(local, file("/source.txt", local, directory = false)),
    ))

    @Test fun selectingAResourceAddsAllItsFoldersAndRollsBackInvalidBatches() {
        val draft = draft()
        val other = destination("/other", local)
        draft.select(other)
        val group = listOf(destination("/one"), destination("/two"))
        assertEquals(CopyDestinationSelectionResult.Added, draft.selectAll(group))
        assertEquals(listOf(other) + group, draft.selected.value)
        assertEquals(CopyDestinationSelectionResult.AlreadySelected, draft.selectAll(group))
        val previous = draft.selected.value
        assertEquals(CopyDestinationSelectionResult.Unavailable,
            draft.selectAll(listOf(destination("/three"), destination("/four").copy(unavailableReason = "Read only"))))
        assertEquals(previous, draft.selected.value)
        assertEquals(CopyDestinationSelectionResult.OverlappingDirectory,
            draft.selectAll(listOf(destination("/new"), destination("/new/child"))))
        assertEquals(previous, draft.selected.value)
    }

    private fun endpoint(protocol: FileProtocol, id: String, separator: String = "/") = CopyEndpointIdentity(
        "runtime", FileEndpointRef(protocol, id), "binding-$id", separator,
    )

    private fun destination(
        path: String,
        endpoint: CopyEndpointIdentity = network,
        reference: StorageReference? = null,
    ) = CopyDestination(CopyFileSnapshot(endpoint, file(path, endpoint).copy(storageReference = reference)), path, "Connection")

    private fun file(path: String, endpoint: CopyEndpointIdentity, directory: Boolean = true) = FileSimpleInfo(
        name = path.replace('\\', '/').substringAfterLast('/'), path = path,
        isDirectory = directory, isHidden = false, mineType = "", size = 0,
        createdDate = 0, updatedDate = 0, protocol = endpoint.endpoint.protocol,
        protocolId = endpoint.endpoint.sourceId,
    )
}
