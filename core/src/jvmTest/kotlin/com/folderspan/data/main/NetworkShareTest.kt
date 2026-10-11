package com.folderspan.data.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkShare
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkShareTest {
    @Test
    fun tagEntriesSetsNetworkProtocolAndId() {
        val shareNetwork = NetworkShare(
            name = "LinkShare",
            baseUrl = "http://localhost:8080",
            password = ""
        )
        val entry = FileSimpleInfo.nullFileSimpleInfo().copy(
            name = "file.txt",
            isDirectory = false,
            path = "/file.txt",
            mineType = "text/plain"
        )
        val tagged = shareNetwork.tagEntries(listOf(entry)).first()
        assertEquals(FileProtocol.Network, tagged.protocol)
        assertEquals(shareNetwork.protocolId, tagged.protocolId)
    }
}
