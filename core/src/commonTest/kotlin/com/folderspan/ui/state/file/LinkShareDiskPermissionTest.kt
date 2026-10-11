package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.DiskMenuPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkShareDiskPermissionTest {
    @Test
    fun filterLinkShareableFilesKeepsOnlyShareableDisks() {
        val allowed = file("allowed.txt")
        val denied = file("denied.txt")
        val missing = file("missing.txt")

        val filtered = filterLinkShareableFiles(listOf(allowed, denied, missing)) { file ->
            when (file.name) {
                allowed.name -> disk(share = true)
                denied.name -> disk(share = false)
                else -> null
            }
        }

        assertEquals(listOf(allowed), filtered)
    }

    @Test
    fun isLinkShareDiskAllowedRequiresExplicitSharePermission() {
        assertTrue(isLinkShareDiskAllowed(disk(share = true)))
        assertFalse(isLinkShareDiskAllowed(disk(share = false)))
        assertFalse(isLinkShareDiskAllowed(null))
    }

    private fun disk(share: Boolean): DiskBase {
        return object : DiskBase() {
            override val name: String = if (share) "allowed" else "denied"
            override val pathSeparator: String = "/"
            override val menuPermission: DiskMenuPermission = DiskMenuPermission(share = share)
        }
    }

    private fun file(name: String): FileSimpleInfo {
        return FileSimpleInfo(
            name = name,
            isDirectory = false,
            isHidden = false,
            path = "/$name",
            mineType = "text/plain",
            size = 1L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
