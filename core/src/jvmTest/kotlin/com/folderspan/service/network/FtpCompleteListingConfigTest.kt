package com.folderspan.service.network

import org.apache.commons.net.ftp.FTPSClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtpCompleteListingConfigTest {
    @Test
    fun ftpAndFtpsRequestHiddenEntriesWithoutWeakeningTlsEndpointChecks() {
        val plain = createFtpTransportClient(false)
        val secure = createFtpTransportClient(true)
        assertTrue(plain.listHiddenFiles)
        assertTrue(secure.listHiddenFiles)
        assertFalse(plain is FTPSClient)
        assertTrue(secure is FTPSClient && secure.isEndpointCheckingEnabled)
    }
}
