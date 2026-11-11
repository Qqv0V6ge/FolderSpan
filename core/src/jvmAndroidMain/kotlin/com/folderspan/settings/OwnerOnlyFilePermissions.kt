package com.folderspan.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal fun restrictOwnerOnly(path: Path, directory: Boolean) {
    try {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        }
        Files.setPosixFilePermissions(path, permissions)
        check(Files.getPosixFilePermissions(path) == permissions) { "Private file permissions could not be verified" }
    } catch (_: UnsupportedOperationException) {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("This file system cannot enforce private owner access")
        restrictOwnerOnlyAcl(acl, directory)
    }
}

/** Windows File.setReadable(false) cannot restrict an ACL; require the actual owner-only list. */
internal fun restrictOwnerOnlyAcl(view: AclFileAttributeView, directory: Boolean) {
    val owner = view.owner
    val builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
        .setPermissions(AclEntryPermission.values().toSet())
    if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
    val expected = listOf(builder.build())
    view.acl = expected
    check(view.owner == owner && view.acl == expected) { "Private owner ACL could not be verified" }
}
