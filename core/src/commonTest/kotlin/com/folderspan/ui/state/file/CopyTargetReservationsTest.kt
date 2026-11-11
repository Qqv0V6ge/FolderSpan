package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CopyTargetReservationsTest {
    @Test
    fun windowsWithDifferentDraftBindingsCannotOverwriteTheSameOutput() = runTest {
        val reservations = CopyTargetReservations { true }
        reservations.claim("one", listOf(output("/target/file", "first-window")))
        assertFailsWith<IllegalArgumentException> {
            reservations.claim("two", listOf(output("/target/file", "second-window")))
        }
    }

    @Test
    fun nestedOutputsConflictButIndependentDirectoriesCanRunTogether() = runTest {
        val reservations = CopyTargetReservations { true }
        reservations.claim("one", listOf(output("/target/folder", "first-window")))
        assertFailsWith<IllegalArgumentException> {
            reservations.claim("two", listOf(output("/target/folder/file", "second-window")))
        }
        reservations.claim("two", listOf(output("/other/file", "second-window")))
    }

    @Test
    fun completedOwnersReleaseTheirReservationsAndRetriesKeepTheirOwn() = runTest {
        val active = mutableSetOf("one")
        val reservations = CopyTargetReservations { it in active }
        reservations.claim("one", listOf(output("/target/file", "first-window")))
        reservations.claim("one", listOf(output("/target/file", "first-window")))
        active.clear()
        active += "two"
        reservations.claim("two", listOf(output("/target/file", "second-window")))
    }

    private fun output(path: String, binding: String) = CopyFileSnapshot(
        CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Local), binding, "/"),
        FileSimpleInfo.pathFileSimpleInfo(path),
    )
}
