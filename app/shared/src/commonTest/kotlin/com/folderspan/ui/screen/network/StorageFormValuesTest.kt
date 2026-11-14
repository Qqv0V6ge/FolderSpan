package com.folderspan.ui.screen.network

import com.folderspan.PRO_AVAILABLE
import com.folderspan.ui.screen.network.form.*

import com.folderspan.data.main.network.NetworkProtocol
import kotlin.test.*

class StorageFormValuesTest {
    @Test
    fun newProviderSelectionMatchesAvailableEdition() {
        val expected = setOf("NFS", "HttpDirectory", "Nextcloud", "Seafile") +
            if (PRO_AVAILABLE) setOf("GoogleDrive", "Dropbox") else emptySet()
        assertEquals(expected, addedStorageProtocols.map { it.name }.toSet())
    }

    @Test
    fun nfsValidatesBothVersionsAndRejectsBadIdentityBeforeSaving() {
        for (version in listOf(3,4)) {
            val form = StorageFormValues(name = "Unix", server = "files.test:2049", nfsVersion = version, uid = "1000", gid = "1000", groups = "1001,1002", export = "/export")
            val network = form.network(NetworkProtocol.NFS.name, "")
            assertEquals(version, network.extras.nfs.version)
            assertEquals(listOf(1001,1002), network.extras.nfs.groups)
            assertFailsWith<IllegalArgumentException> { form.copy(uid = "-1").network("NFS","") }
            assertFailsWith<IllegalArgumentException> { form.copy(export = "relative").network("NFS","") }
        }
    }

    @Test
    fun cloudConnectionsSaveOnlyAccountReferencesAndKeepIdentityOnRename() {
        val form = StorageFormValues(name = "Drive", account = "account-reference", connection = "stable-connection", root = "root-id")
        val before = form.network("GoogleDrive", "never-persist-this-token")
        val after = form.copy(name = "Work", root = "another-root").network("GoogleDrive","another-token")
        assertEquals(before.protocolId, after.protocolId)
        assertEquals("", before.password)
        assertEquals("account-reference", before.extras.cloud.accountId)
        assertFailsWith<IllegalArgumentException> { form.copy(account = "").network("GoogleDrive","") }
    }

    @Test
    fun blankNamesUseTheServerOrProviderAndCustomNamesAreKept() {
        for (protocol in listOf("FTP", "SFTP", "SMB", "NFS", "WebDav", "S3", "HttpDirectory", "Nextcloud", "Seafile")) {
            val host = if (protocol in listOf("FTP", "SFTP", "SMB", "NFS")) "nas.example.test" else "https://nas.example.test/files/"
            assertEquals("nas.example.test", defaultNetworkName(protocol, host), protocol)
        }
        assertEquals("Google Drive", defaultNetworkName("GoogleDrive", ""))
        assertEquals("Dropbox", defaultNetworkName("Dropbox", ""))
        val form = StorageFormValues(server = "https://mirror.example.test/files/")
        assertEquals("mirror.example.test", form.network("HttpDirectory", "").name)
        assertEquals("My files", form.copy(name = " My files ").network("HttpDirectory", "").name)
        assertEquals("Google Drive", StorageFormValues(account = "account", connection = "connection").network("GoogleDrive", "").name)
    }

    @Test
    fun newProtocolsReportFieldErrorsAndRejectInvalidValues() {
        val nfs = StorageFormValues(server = "nas.example.test")
        for ((field, invalid) in listOf(
            "server" to nfs.copy(server = ""),
            "export" to nfs.copy(export = "relative"),
            "uid" to nfs.copy(uid = "-1"),
            "gid" to nfs.copy(gid = "2147483648"),
            "groups" to nfs.copy(groups = "1,,2"),
            "groups" to nfs.copy(groups = (0..16).joinToString(",")),
            "mountPort" to nfs.copy(nfsVersion = 3, mountPort = "65536"),
        )) {
            assertTrue(field in invalid.validationErrors("NFS"), field)
            assertFailsWith<IllegalArgumentException>(field) { invalid.network("NFS", "") }
        }
        assertTrue(nfs.copy(uid = "2147483647", groups = "0,1", nfsVersion = 3, mountPort = "65535").validationErrors("NFS").isEmpty())
        assertTrue(nfs.copy(mountPort = "hidden-invalid-port").validationErrors("NFS").isEmpty())
        for (protocol in listOf("HttpDirectory", "Nextcloud", "Seafile")) {
            for (server in listOf("", "mirror.test", "ftp://mirror.test", "https://u:p@mirror.test", "https://mirror.test/?query=1", "https://mirror.test/#fragment")) {
                val invalid = StorageFormValues(server = server)
                assertTrue("server" in invalid.validationErrors(protocol), "$protocol: $server")
                assertFailsWith<IllegalArgumentException> { invalid.network(protocol, "") }
            }
        }
        for (protocol in listOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) {
            assertTrue("account" in StorageFormValues().validationErrors(protocol))
        }
        assertTrue("library" in StorageFormValues(library = "bad-id").validationErrors("Seafile"))
    }
}
