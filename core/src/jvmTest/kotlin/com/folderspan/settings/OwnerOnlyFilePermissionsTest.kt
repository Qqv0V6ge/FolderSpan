package com.folderspan.settings

import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OwnerOnlyFilePermissionsTest {
    private val owner = UserPrincipal { "fixture-owner" }
    private val other = UserPrincipal { "unrelated-user" }

    @Test
    fun ownerAclRemovesOtherPrincipalsAndAllowsOwnerRecovery() {
        val view = FixtureAclView(owner)
        view.entries = mutableListOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
            .setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build())
        restrictOwnerOnlyAcl(view, directory = false)
        val entry = view.acl.single()
        assertEquals(owner, entry.principal())
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
        assertEquals(emptySet(), entry.flags())
    }

    @Test
    fun directoryOwnerAclAlsoProtectsNewChildren() {
        val view = FixtureAclView(owner)
        restrictOwnerOnlyAcl(view, directory = true)
        assertEquals(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT),
            view.acl.single().flags())
    }

    @Test
    fun silentAdditionalGrantAndChangedOwnerAreRejected() {
        val extra = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
            .setPermissions(AclEntryPermission.READ_DATA).build()
        assertFails {
            restrictOwnerOnlyAcl(FixtureAclView(owner, afterWrite = { it.entries.add(extra) }), false)
        }
        assertFails {
            restrictOwnerOnlyAcl(FixtureAclView(owner, afterWrite = { it.currentOwner = other }), false)
        }
    }

    private class FixtureAclView(
        var currentOwner: UserPrincipal,
        private val afterWrite: (FixtureAclView) -> Unit = {},
    ) : AclFileAttributeView {
        var entries = mutableListOf<AclEntry>()
        override fun name() = "acl"
        override fun getOwner(): UserPrincipal = currentOwner
        override fun setOwner(owner: UserPrincipal) { currentOwner = owner }
        override fun getAcl(): MutableList<AclEntry> = entries.toMutableList()
        override fun setAcl(acl: MutableList<AclEntry>) {
            entries = acl.toMutableList()
            afterWrite(this)
        }
    }
}
