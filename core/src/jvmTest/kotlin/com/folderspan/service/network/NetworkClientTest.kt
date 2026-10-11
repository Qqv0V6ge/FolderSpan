package com.folderspan.service.network

import com.folderspan.exception.NetworkUnsupportedException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import strings.AppStrings

class NetworkClientTest {
    @Test
    fun copyFileDefaultsToExplicitUnsupportedFailure() = runBlocking {
        val client: NetworkClient = UnsupportedNetworkClient(AppStrings.ui_test_network_client_testing_protocol_unavailable)

        val result = client.copyFile("/source.txt", "/target.txt")

        assertTrue(result.isFailure)
        val failure = assertIs<NetworkUnsupportedException>(result.exceptionOrNull())
        assertEquals(
            AppStrings.ui_current_network_protocol_does_not_support_remote_single_file_replication.format(arg0 = "/source.txt", arg1 = "/target.txt"),
            failure.message,
        )
    }
}
